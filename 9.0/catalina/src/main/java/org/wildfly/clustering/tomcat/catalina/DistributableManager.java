/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.marshalling.Marshallability;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;

/**
 * Adapts a WildFly distributable SessionManager to Tomcat's Manager interface.
 * @author Paul Ferraro
 */
public class DistributableManager implements CatalinaManager {
	private static final char ROUTE_DELIMITER = '.';

	private final SessionManager<CatalinaSessionContext> manager;
	private final UnaryOperator<String> affinity;
	private final Context context;
	private final Consumer<ImmutableSession> invalidateAction;
	private final Marshallability marshallability;

	public DistributableManager(SessionManager<CatalinaSessionContext> manager, UnaryOperator<String> affinity, Context context, Marshallability marshallability) {
		this.manager = manager;
		this.affinity = affinity;
		this.marshallability = marshallability;
		this.context = context;
		this.invalidateAction = new CatalinaSessionDestroyAction(context);
	}

	@Override
	public SessionManager<CatalinaSessionContext> getSessionManager() {
		return this.manager;
	}

	@Override
	public Marshallability getMarshallability() {
		return this.marshallability;
	}

	/**
	 * Strips routing information from requested session identifier.
	 */
	private static String parseSessionId(String requestedSesssionId) {
		int index = requestedSesssionId.indexOf(ROUTE_DELIMITER);
		return (index < 0) ? requestedSesssionId : requestedSesssionId.substring(0, index);
	}

	/**
	 * Appends routing information to session identifier.
	 */
	private org.apache.catalina.Session getSession(Session<CatalinaSessionContext> session, Batch batch) {
		String id = session.getId();
		String route = this.affinity.apply(id);
		String internalId = new StringBuilder(id.length() + route.length() + 1).append(id).append(ROUTE_DELIMITER).append(route).toString();
		return new DistributableSession(this, session, internalId, batch.suspend(), () -> this.invalidateAction.accept(session));
	}

	@Override
	public void start() {
		this.manager.start();
	}

	@Override
	public void stop() {
		this.manager.stop();
	}

	@Override
	public org.apache.catalina.Session createSession(String sessionId) {
		String id = (sessionId != null) ? parseSessionId(sessionId) : this.manager.getIdentifierFactory().get();
		boolean close = true;
		// Batch will be closed by Session.close();
		Batch batch = this.manager.getBatchFactory().get();
		try {
			Session<CatalinaSessionContext> session = this.manager.createSession(id);
			HttpSessionEvent event = new HttpSessionEvent(HttpSessionProvider.INSTANCE.asSession(session, this.context.getServletContext()));
			Stream.of(this.context.getApplicationLifecycleListeners()).filter(HttpSessionListener.class::isInstance).map(HttpSessionListener.class::cast).forEach(listener -> {
				try {
					this.context.fireContainerEvent("beforeSessionCreated", listener);
					listener.sessionCreated(event);
				} catch (Throwable e) {
					this.context.getLogger().warn(e.getMessage(), e);
				} finally {
					this.context.fireContainerEvent("afterSessionCreated", listener);
				}
			});
			org.apache.catalina.Session result = this.getSession(session, batch);
			close = false;
			return result;
		} catch (RuntimeException | Error e) {
			batch.discard();
			throw e;
		} finally {
			if (close) {
				batch.close();
			}
		}
	}

	@Override
	public org.apache.catalina.Session findSession(String sessionId) throws IOException {
		String id = parseSessionId(sessionId);
		boolean close = true;
		// Batch will be closed by Session.close();
		Batch batch = this.manager.getBatchFactory().get();
		try {
			Session<CatalinaSessionContext> session = this.manager.findSession(id);
			if (session == null) {
				return null;
			}
			org.apache.catalina.Session result = this.getSession(session, batch);
			close = false;
			return result;
		} catch (RuntimeException | Error e) {
			batch.discard();
			throw e;
		} finally {
			if (close) {
				batch.close();
			}
		}
	}

	@Override
	public void changeSessionId(org.apache.catalina.Session session) {
		this.changeSessionId(session, this.manager.getIdentifierFactory().get());
	}

	@Override
	public void changeSessionId(org.apache.catalina.Session session, String id) {
		session.tellChangedSessionId(id, session.getId(), true, true);
	}

	@Override
	public Context getContext() {
		return this.context;
	}

	@Override
	public boolean willAttributeDistribute(String name, Object value) {
		return this.marshallability.isMarshallable(value);
	}

	@Override
	public boolean getNotifyAttributeListenerOnUnchangedValue() {
		return false;
	}
}
