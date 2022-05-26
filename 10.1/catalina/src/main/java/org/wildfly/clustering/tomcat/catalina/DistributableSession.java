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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.web.session.Session;
import org.wildfly.clustering.web.session.oob.OOBSession;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;

/**
 * Adapts a WildFly distributable Session to Tomcat's Session interface.
 * @author Paul Ferraro
 */
public class DistributableSession<B extends Batch> implements CatalinaSession {

    private final CatalinaManager<B> manager;
    private final AtomicReference<Session<LocalSessionContext>> session;
    private final String internalId;
    private final B batch;
    private final Runnable invalidateAction;
    private final Runnable closeTask;
    private final Instant startTime;

    public DistributableSession(CatalinaManager<B> manager, Session<LocalSessionContext> session, String internalId, B batch, Runnable invalidateAction, Runnable closeTask) {
        this.manager = manager;
        this.session = new AtomicReference<>(session);
        this.internalId = internalId;
        this.batch = batch;
        this.invalidateAction = invalidateAction;
        this.closeTask = closeTask;
        this.startTime = session.getMetaData().isNew() ? session.getMetaData().getCreationTime() : Instant.now();
    }

    @Override
    public String getAuthType() {
        return this.session.get().getLocalContext().getAuthType();
    }

    @Override
    public void setAuthType(String authType) {
        this.session.get().getLocalContext().setAuthType(authType);
    }

    @Override
    public long getCreationTime() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.resumeBatch()) {
            return session.getMetaData().getCreationTime().toEpochMilli();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(session);
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
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.resumeBatch()) {
            return session.getMetaData().getLastAccessStartTime().toEpochMilli();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(session);
            throw e;
        }
    }

    @Override
    public Manager getManager() {
        return this.manager;
    }

    @Override
    public int getMaxInactiveInterval() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.resumeBatch()) {
            return (int) session.getMetaData().getMaxInactiveInterval().getSeconds();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(session);
            throw e;
        }
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.resumeBatch()) {
            session.getMetaData().setMaxInactiveInterval((interval > 0) ? Duration.ofSeconds(interval) : Duration.ZERO);
        } catch (IllegalStateException e) {
            this.closeIfInvalid(session);
            throw e;
        }
    }

    @Override
    public Principal getPrincipal() {
        return this.session.get().getLocalContext().getPrincipal();
    }

    @Override
    public void setPrincipal(Principal principal) {
        this.session.get().getLocalContext().setPrincipal(principal);
    }

    @Override
    public HttpSession getSession() {
        return new HttpSessionAdapter<>(this.session, this.manager, this.batch, this.invalidateAction, this::closeIfInvalid);
    }

    @Override
    public boolean isValid() {
        return this.session.get().isValid();
    }

    @Override
    public void addSessionListener(SessionListener listener) {
        this.session.get().getLocalContext().getSessionListeners().add(listener);
    }

    @Override
    public void endAccess() {
        Batcher<B> batcher = this.manager.getSessionManager().getBatcher();
        Session<LocalSessionContext> requestSession = this.session.get();
        try (BatchContext context = batcher.resumeBatch(this.batch)) {
            // If batch was discarded, close it
            if (this.batch.getState() == Batch.State.DISCARDED) {
                this.batch.close();
            }
            // If batch is closed, close valid session in a new batch
            try (Batch batch = (this.batch.getState() == Batch.State.CLOSED) && requestSession.isValid() ? batcher.createBatch() : this.batch) {
                // Ensure session is closed, even if invalid
                try (Session<LocalSessionContext> session = requestSession) {
                    if (session.isValid()) {
                        // According to §7.6 of the servlet specification:
                        // The session is considered to be accessed when a request that is part of the session is first handled by the servlet container.
                        session.getMetaData().setLastAccess(this.startTime, Instant.now());
                    }
                }
            }
        } catch (Throwable e) {
            // Don't propagate exceptions at the stage, since response was already committed
            this.manager.getContext().getLogger().warn(e.getLocalizedMessage(), e);
        } finally {
            // Dereference the distributed session, but retain reference to session identifier and local context
            // If session is accessed after this method, getSessionEntry() will lazily create an OOB session
            // Switch to OOB session, in case this session is referenced outside the scope of this request
            this.session.set(new OOBSession<>(this.manager.getSessionManager(), requestSession.getId(), requestSession.getLocalContext()));
            this.closeTask.run();
        }
    }

    @Override
    public void expire() {
        // Expiration not handled here
        throw new IllegalStateException();
    }

    @Override
    public Object getNote(String name) {
        return this.session.get().getLocalContext().getNotes().get(name);
    }

    @Override
    public Iterator<String> getNoteNames() {
        return this.session.get().getLocalContext().getNotes().keySet().iterator();
    }

    @Override
    public void removeNote(String name) {
        this.session.get().getLocalContext().getNotes().remove(name);
    }

    @Override
    public void removeSessionListener(SessionListener listener) {
        this.session.get().getLocalContext().getSessionListeners().remove(listener);
    }

    @Override
    public void setNote(String name, Object value) {
        this.session.get().getLocalContext().getNotes().put(name, value);
    }

    @Override
    public void tellChangedSessionId(String newId, String oldId, boolean notifySessionListeners, boolean notifyContainerListeners) {
        Session<LocalSessionContext> oldSession = this.session.get();
        try (BatchContext context = this.resumeBatch()) {
            Session<LocalSessionContext> newSession = this.manager.getSessionManager().createSession(newId);
            try {
                for (String name: oldSession.getAttributes().getAttributeNames()) {
                    newSession.getAttributes().setAttribute(name, oldSession.getAttributes().getAttribute(name));
                }
                newSession.getMetaData().setMaxInactiveInterval(oldSession.getMetaData().getMaxInactiveInterval());
                newSession.getMetaData().setLastAccess(oldSession.getMetaData().getLastAccessStartTime(), oldSession.getMetaData().getLastAccessEndTime());
                newSession.getLocalContext().setAuthType(oldSession.getLocalContext().getAuthType());
                newSession.getLocalContext().setPrincipal(oldSession.getLocalContext().getPrincipal());
                oldSession.invalidate();
                this.session.set(newSession);
            } catch (IllegalStateException e) {
                this.closeIfInvalid(oldSession);
                newSession.invalidate();
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

    private BatchContext resumeBatch() {
        B batch = (this.batch.getState() != Batch.State.CLOSED) ? this.batch : null;
        return this.manager.getSessionManager().getBatcher().resumeBatch(batch);
    }

    private void closeIfInvalid(Session<LocalSessionContext> session) {
        if (!session.isValid()) {
            // If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
            try {
                session.close();
            } finally {
                // Ensure close task is run
                this.closeTask.run();
            }
        }
    }
}
