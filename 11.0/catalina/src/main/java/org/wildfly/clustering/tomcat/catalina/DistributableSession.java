/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.security.Principal;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;

import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.SuspendedBatch;
import org.wildfly.clustering.context.Context;
import org.wildfly.clustering.session.Session;

/**
 * Adapts a WildFly distributable Session to Tomcat's Session interface.
 * @author Paul Ferraro
 */
public class DistributableSession implements CatalinaSession {

	private final CatalinaManager manager;
	private final AtomicReference<Session<CatalinaSessionContext>> reference;
	private final String internalId;
	private final Instant startTime;
	private final SuspendedBatch batch;
	private final Runnable closeTask;
	private final HttpSession session;

	public DistributableSession(CatalinaManager manager, Session<CatalinaSessionContext> session, String internalId, SuspendedBatch batch, Runnable closeTask) {
		this.manager = manager;
		this.reference = new AtomicReference<>(session);
		this.internalId = internalId;
		this.startTime = session.getMetaData().isNew() ? session.getMetaData().getCreationTime() : Instant.now();
		this.batch = batch;
		this.closeTask = closeTask;
		this.session = new HttpSessionAdapter(this.manager, this.reference::get, batch, closeTask);
	}

	@Override
	public String getAuthType() {
		return this.reference.get().getContext().getAuthType();
	}

	@Override
	public void setAuthType(String authType) {
		this.reference.get().getContext().setAuthType(authType);
	}

	@Override
	public boolean isNew() {
		return this.session.isNew();
	}

	@Override
	public long getCreationTime() {
		return this.session.getCreationTime();
	}

	@Override
	public String getId() {
		return this.reference.get().getId();
	}

	@Override
	public String getIdInternal() {
		return this.internalId;
	}

	@Override
	public long getLastAccessedTime() {
		return this.session.getLastAccessedTime();
	}

	@Override
	public Manager getManager() {
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
		return this.reference.get().getContext().getPrincipal();
	}

	@Override
	public void setPrincipal(Principal principal) {
		this.reference.get().getContext().setPrincipal(principal);
	}

	@Override
	public HttpSession getSession() {
		return this.session;
	}

	@Override
	public boolean isValid() {
		return this.reference.get().isValid();
	}

	@Override
	public void addSessionListener(SessionListener listener) {
		this.reference.get().getContext().getSessionListeners().add(listener);
	}

	@Override
	public void endAccess() {
		try (Context<Batch> context = this.batch.resumeWithContext()) {
			try (Batch batch = context.get()) {
				try (Session<CatalinaSessionContext> session = this.reference.get()) {
					if (session.isValid()) {
						// According to §7.6 of the servlet specification:
						// The session is considered to be accessed when a request that is part of the session is first handled
						// by the servlet container.
						session.getMetaData().setLastAccess(this.startTime, Instant.now());
					}
				} catch (Throwable e) {
					// Don't propagate exceptions at the stage, since response was already committed
					this.manager.getContext().getLogger().warn(e.getLocalizedMessage(), e);
				}
			} finally {
				this.closeTask.run();
			}
		}
	}

	@Override
	public void expire() {
		// Expiration not handled here
		throw new IllegalStateException();
	}

	@Override
	public Object getNote(String name) {
		return this.reference.get().getContext().getNotes().get(name);
	}

	@Override
	public Iterator<String> getNoteNames() {
		return this.reference.get().getContext().getNotes().keySet().iterator();
	}

	@Override
	public void removeNote(String name) {
		this.reference.get().getContext().getNotes().remove(name);
	}

	@Override
	public void removeSessionListener(SessionListener listener) {
		this.reference.get().getContext().getSessionListeners().remove(listener);
	}

	@Override
	public void setNote(String name, Object value) {
		this.reference.get().getContext().getNotes().put(name, value);
	}

	@Override
	public void tellChangedSessionId(String newId, String oldId, boolean notifySessionListeners, boolean notifyContainerListeners) {
		Session<CatalinaSessionContext> oldSession = this.reference.get();
		try (Context<Batch> context = this.batch.resumeWithContext()) {
			Session<CatalinaSessionContext> newSession = this.manager.getSessionManager().createSession(newId);
			try {
				for (Map.Entry<String, Object> entry : oldSession.getAttributes().entrySet()) {
					newSession.getAttributes().put(entry.getKey(), entry.getValue());
				}
				newSession.getMetaData().setTimeout(oldSession.getMetaData().getTimeout());
				newSession.getMetaData().setLastAccess(oldSession.getMetaData().getLastAccessStartTime(), oldSession.getMetaData().getLastAccessTime());
				newSession.getContext().setAuthType(oldSession.getContext().getAuthType());
				newSession.getContext().setPrincipal(oldSession.getContext().getPrincipal());
				this.reference.set(newSession);
				oldSession.invalidate();
			} catch (IllegalStateException e) {
				newSession.invalidate();
				throw e;
			}
		} catch (IllegalStateException e) {
			if (!oldSession.isValid()) {
				try (Context<Batch> context = this.batch.resumeWithContext()) {
					try (Batch batch = context.get()) {
						oldSession.close();
					} finally {
						this.closeTask.run();
					}
				}
			}
		}

		// Invoke listeners outside of the context of the batch associated with this session
		org.apache.catalina.Context context = this.manager.getContext();

		if (notifyContainerListeners) {
			context.fireContainerEvent(org.apache.catalina.Context.CHANGE_SESSION_ID_EVENT, new String[] { oldId, newId });
		}

		if (notifySessionListeners) {
			HttpSessionEvent event = new HttpSessionEvent(this.getSession());
			Stream.of(context.getApplicationEventListeners()).filter(listener -> listener instanceof HttpSessionIdListener).map(listener -> (HttpSessionIdListener) listener).forEach(listener -> {
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
		return this.manager.getMarshallability().isMarshallable(value);
	}
}
