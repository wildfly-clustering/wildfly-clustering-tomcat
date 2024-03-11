/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.tomcat.catalina;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;

import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.BatchContext;
import org.wildfly.clustering.session.Session;

/**
 * Adapts a WildFly distributable Session to Tomcat's Session interface.
 * @author Paul Ferraro
 */
public class DistributableSession<B extends Batch> implements CatalinaSession {

	private final CatalinaManager<B> manager;
	private final AtomicReference<Session<CatalinaSessionContext>> session;
	private final String internalId;
	private final B batch;
	private final Runnable invalidateAction;
	private final Instant startTime;

	public DistributableSession(CatalinaManager<B> manager, Session<CatalinaSessionContext> session, String internalId, B batch, Runnable invalidateAction) {
		this.manager = manager;
		this.session = new AtomicReference<>(session);
		this.internalId = internalId;
		this.batch = batch;
		this.invalidateAction = invalidateAction;
		this.startTime = session.getMetaData().isNew() ? session.getMetaData().getCreationTime() : Instant.now();
	}

	@Override
	public String getAuthType() {
		return this.session.get().getContext().getAuthType();
	}

	@Override
	public void setAuthType(String authType) {
		this.session.get().getContext().setAuthType(authType);
	}

	@Override
	public long getCreationTime() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return session.getMetaData().getCreationTime().toEpochMilli();
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
	}

	@Override
	public String getId() {
		return this.session.get().getId();
	}

	@Override
	public String getIdInternal() {
		return this.internalId;
	}

	@Override
	public long getLastAccessedTime() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return session.getMetaData().getLastAccessStartTime().toEpochMilli();
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
	}

	@Override
	public Manager getManager() {
		return this.manager;
	}

	@Override
	public int getMaxInactiveInterval() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return (int) session.getMetaData().getTimeout().getSeconds();
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			session.getMetaData().setTimeout((interval > 0) ? Duration.ofSeconds(interval) : Duration.ZERO);
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
	}

	@Override
	public Principal getPrincipal() {
		return this.session.get().getContext().getPrincipal();
	}

	@Override
	public void setPrincipal(Principal principal) {
		this.session.get().getContext().setPrincipal(principal);
	}

	@Override
	public HttpSession getSession() {
		return new HttpSessionAdapter<>(this.session, this.manager, this.batch, this.invalidateAction);
	}

	@Override
	public boolean isValid() {
		return this.session.get().isValid();
	}

	@Override
	public void addSessionListener(SessionListener listener) {
		this.session.get().getContext().getSessionListeners().add(listener);
	}

	@Override
	public void endAccess() {
		try (BatchContext<B> context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
			try (B batch = context.getBatch()) {
				try (Session<CatalinaSessionContext> session = this.session.get()) {
					if (session.isValid()) {
						// According to §7.6 of the servlet specification:
						// The session is considered to be accessed when a request that is part of the session is first handled
						// by the servlet container.
						session.getMetaData().setLastAccess(this.startTime, Instant.now());
					}
				}
			}
		} catch (Throwable e) {
			// Don't propagate exceptions at the stage, since response was already committed
			this.manager.getContext().getLogger().warn(e.getLocalizedMessage(), e);
		}
	}

	@Override
	public void expire() {
		// Expiration not handled here
		throw new IllegalStateException();
	}

	@Override
	public Object getNote(String name) {
		return this.session.get().getContext().getNotes().get(name);
	}

	@Override
	public Iterator<String> getNoteNames() {
		return this.session.get().getContext().getNotes().keySet().iterator();
	}

	@Override
	public void removeNote(String name) {
		this.session.get().getContext().getNotes().remove(name);
	}

	@Override
	public void removeSessionListener(SessionListener listener) {
		this.session.get().getContext().getSessionListeners().remove(listener);
	}

	@Override
	public void setNote(String name, Object value) {
		this.session.get().getContext().getNotes().put(name, value);
	}

	@Override
	public void tellChangedSessionId(String newId, String oldId, boolean notifySessionListeners, boolean notifyContainerListeners) {
		try (BatchContext<B> context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
			try (B batch = context.getBatch()) {
				Session<CatalinaSessionContext> oldSession = this.session.get();
				Session<CatalinaSessionContext> newSession = this.manager.getSessionManager().createSession(newId);
				try {
					for (Map.Entry<String, Object> entry : oldSession.getAttributes().entrySet()) {
						newSession.getAttributes().put(entry.getKey(), entry.getValue());
					}
					newSession.getMetaData().setTimeout(oldSession.getMetaData().getTimeout());
					newSession.getMetaData().setLastAccess(oldSession.getMetaData().getLastAccessStartTime(), oldSession.getMetaData().getLastAccessTime());
					newSession.getContext().setAuthType(oldSession.getContext().getAuthType());
					newSession.getContext().setPrincipal(oldSession.getContext().getPrincipal());
					this.session.set(newSession);
					oldSession.invalidate();
				} catch (IllegalStateException e) {
					if (!oldSession.isValid()) {
						// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
						oldSession.close();
					}
					newSession.invalidate();
				}
			}
		}

		// Invoke listeners outside of the context of the batch associated with this session
		Context context = this.manager.getContext();

		if (notifyContainerListeners) {
			context.fireContainerEvent(Context.CHANGE_SESSION_ID_EVENT, new String[] { oldId, newId });
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
