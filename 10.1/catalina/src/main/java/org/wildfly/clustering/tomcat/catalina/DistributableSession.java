/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.security.Principal;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;

import org.apache.catalina.SessionListener;
import org.wildfly.clustering.function.BiConsumer;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.Function;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.server.util.BlockingReference;
import org.wildfly.clustering.server.util.Reference;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.SessionMetaData;

/**
 * Adapts a WildFly distributable Session to Tomcat's Session interface.
 * @author Paul Ferraro
 */
public class DistributableSession implements CatalinaSession {
	private static final System.Logger LOGGER = System.getLogger(DistributableSession.class.getCanonicalName());

	private static final Function<CatalinaSessionContext, String> GET_AUTH_TYPE = CatalinaSessionContext::getAuthType;
	private static final BiConsumer<CatalinaSessionContext, String> SET_AUTH_TYPE = CatalinaSessionContext::setAuthType;
	private static final Function<CatalinaSessionContext, Principal> GET_PRINCIPAL = CatalinaSessionContext::getPrincipal;
	private static final BiConsumer<CatalinaSessionContext, Principal> SET_PRINCIPAL = CatalinaSessionContext::setPrincipal;
	private static final Function<CatalinaSessionContext, List<SessionListener>> LISTENERS = CatalinaSessionContext::getSessionListeners;
	private static final BiConsumer<List<SessionListener>, SessionListener> ADD_LISTENER = List::add;
	private static final BiConsumer<List<SessionListener>, SessionListener> REMOVE_LISTENER = List::remove;

	private final CatalinaManager manager;
	private final BlockingReference<Session<CatalinaSessionContext>> reference;
	private final Reference.Reader<CatalinaSessionContext> contextReader;
	private final Reference.Reader<Map<String, Object>> notesReader;
	private final Reference.Reader<List<SessionListener>> listenersReader;
	private final Instant startTime;
	private final AtomicReference<Runnable> closeTask;
	private final HttpSession session;

	/**
	 * Creates a distributable session.
	 * @param manager the manager of this session.
	 * @param session the decorated session
	 * @param closeTask a task to invoke on {@link #endAccess()}.
	 */
	public DistributableSession(CatalinaManager manager, Session<CatalinaSessionContext> session, Runnable closeTask) {
		this.manager = manager;
		this.reference = BlockingReference.of(session);
		this.contextReader = this.reference.getReader().map(DistributableHttpSession.CONTEXT);
		this.notesReader = this.reference.getReader().map(DistributableHttpSession.NOTES);
		this.listenersReader = this.contextReader.map(LISTENERS);
		this.startTime = session.getMetaData().getLastAccessTime().isEmpty() ? session.getMetaData().getCreationTime() : Instant.now();
		this.closeTask = new AtomicReference<>(closeTask);
		this.session = new DistributableHttpSession(this.manager, this.reference, this.closeTask);
	}

	@Override
	public String getAuthType() {
		return this.contextReader.map(GET_AUTH_TYPE).get();
	}

	@Override
	public void setAuthType(String authType) {
		this.contextReader.read(SET_AUTH_TYPE.composeUnary(Function.identity(), Function.of(authType)));
	}

	@Override
	public long getCreationTime() {
		return this.session.getCreationTime();
	}

	@Override
	public String getId() {
		return this.session.getId();
	}

	@Override
	public long getLastAccessedTime() {
		return this.session.getLastAccessedTime();
	}

	@Override
	public CatalinaManager getManager() {
		return this.manager;
	}

	@Override
	public int getMaxInactiveInterval() {
		return this.session.getMaxInactiveInterval();
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		this.session.setMaxInactiveInterval(interval);
	}

	@Override
	public Principal getPrincipal() {
		return this.contextReader.map(GET_PRINCIPAL).get();
	}

	@Override
	public void setPrincipal(Principal principal) {
		this.contextReader.read(SET_PRINCIPAL.composeUnary(Function.identity(), Function.of(principal)));
	}

	@Override
	public HttpSession getSession() {
		return this.session;
	}

	@Override
	public boolean isValid() {
		return this.reference.getReader().map(DistributableHttpSession.VALID.thenBox()).get();
	}

	@Override
	public void endAccess() {
		// Guard against duplicate calls
		Runnable closeTask = this.closeTask.getAndSet(null);
		if (closeTask != null) {
			try {
				this.reference.getReader().read(completeSession -> {
					// Ensure session is closed, even if invalid
					try (Session<CatalinaSessionContext> session = completeSession) {
						LOGGER.log(System.Logger.Level.TRACE, "DistributableSession.endAccess() for {0}", session.getId());
						if (session.isValid()) {
							// According to §7.6 of the servlet specification:
							// The session is considered to be accessed when a request that is part of the session is first handled by the servlet container.
							session.getMetaData().setLastAccess(this.startTime, Instant.now());
						}
					} catch (Throwable e) {
						// Don't propagate exceptions at the stage, since response was already committed
						this.manager.getContext().getLogger().warn(e.getLocalizedMessage(), e);
					}
				});
			} finally {
				closeTask.run();
			}
		}
	}

	@Override
	public void addSessionListener(SessionListener listener) {
		this.listenersReader.read(ADD_LISTENER.composeUnary(Function.identity(), Function.of(listener)));
	}

	@Override
	public void removeSessionListener(SessionListener listener) {
		this.listenersReader.read(REMOVE_LISTENER.composeUnary(Function.identity(), Function.of(listener)));
	}

	@Override
	public Object getNote(String name) {
		return this.notesReader.map(DistributableHttpSession.GET_ATTRIBUTE.composeUnary(Function.identity(), Function.of(name))).get();
	}

	@Override
	public Iterator<String> getNoteNames() {
		return this.notesReader.map(DistributableHttpSession.ATTRIBUTE_NAMES).get().iterator();
	}

	@Override
	public void removeNote(String name) {
		this.notesReader.map(DistributableHttpSession.REMOVE_ATTRIBUTE.composeUnary(Function.identity(), Function.of(name))).get();
	}

	@Override
	public void setNote(String name, Object value) {
		if (value != null) {
			this.notesReader.map(DistributableHttpSession.SET_ATTRIBUTE.composeUnary(Function.identity(), Function.of(Map.entry(name, value)))).get();
		} else {
			this.removeNote(name);
		}
	}

	@Override
	public void setId(String id) {
		SessionManager<CatalinaSessionContext> manager = this.manager.getSessionManager();
		this.reference.getWriter(Session::isValid).update(new UnaryOperator<>() {
			@Override
			public Session<CatalinaSessionContext> apply(Session<CatalinaSessionContext> currentSession) {
				SessionMetaData currentMetaData = currentSession.getMetaData();
				Map<String, Object> currentAttributes = currentSession.getAttributes();
				Session<CatalinaSessionContext> newSession = manager.createSession(id);
				try {
					newSession.getAttributes().putAll(currentAttributes);
					SessionMetaData newMetaData = newSession.getMetaData();
					currentMetaData.getMaxIdle().ifPresent(newMetaData::setMaxIdle);
					currentMetaData.getLastAccess().ifPresent(newMetaData::setLastAccess);
					newSession.getContext().setAuthType(currentSession.getContext().getAuthType());
					newSession.getContext().setPrincipal(currentSession.getContext().getPrincipal());
					newSession.getContext().getNotes().putAll(currentSession.getContext().getNotes());
					currentSession.invalidate();
					return newSession;
				} catch (RuntimeException | Error e) {
					newSession.invalidate();
					throw e;
				} finally {
					Consumer.close().accept(newSession.isValid() ? currentSession : newSession);
				}
			}
		});
	}

	@Override
	public void tellChangedSessionId(String newId, String oldId, boolean notifySessionListeners, boolean notifyContainerListeners) {
		org.apache.catalina.Context context = this.manager.getContext();

		if (notifyContainerListeners) {
			context.fireContainerEvent(org.apache.catalina.Context.CHANGE_SESSION_ID_EVENT, new String[] { oldId, newId });
		}

		if (notifySessionListeners) {
			HttpSessionEvent event = new HttpSessionEvent(this.getSession());
			Stream.of(context.getApplicationEventListeners()).filter(HttpSessionIdListener.class::isInstance).map(HttpSessionIdListener.class::cast).forEach(listener -> {
				try {
					listener.sessionIdChanged(event, oldId);
				} catch (Throwable e) {
					context.getLogger().warn(e.getMessage(), e);
				}
			});
		}
	}

	@Override
	public boolean isAttributeDistributable(String name, Object value) {
		return this.manager.getMarshallability().test(value);
	}
}
