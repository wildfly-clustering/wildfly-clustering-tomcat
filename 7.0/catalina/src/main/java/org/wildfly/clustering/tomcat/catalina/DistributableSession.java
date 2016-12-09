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
import java.util.Iterator;

import javax.servlet.http.HttpSession;

import org.apache.catalina.Context;
import org.apache.catalina.Manager;
import org.apache.catalina.SessionListener;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.web.session.Session;
import org.wildfly.clustering.web.session.SessionManager;

/**
 * Adapts a WildFly distributable Session to Tomcat's Session interface.
 * @author Paul Ferraro
 */
public class DistributableSession implements TomcatSession {

    private final TomcatManager manager;
    private final String internalId;
    private final Batch batch;
    private final Runnable invalidateAction;
    private final Runnable closeTask;

    private volatile Session<LocalSessionContext> session;

    public DistributableSession(TomcatManager manager, Session<LocalSessionContext> session, String internalId, Batch batch, Runnable invalidateAction, Runnable closeTask) {
        this.manager = manager;
        this.session = session;
        this.internalId = internalId;
        this.batch = batch;
        this.invalidateAction = invalidateAction;
        this.closeTask = closeTask;
    }

    @Override
    public String getAuthType() {
        return this.session.getLocalContext().getAuthType();
    }

    @Override
    public void setAuthType(String authType) {
        this.session.getLocalContext().setAuthType(authType);
    }

    @Override
    public long getCreationTime() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return this.session.getMetaData().getCreationTime().toEpochMilli();
        }
    }

    @Override
    public String getId() {
        return this.session.getId();
    }

    @Override
    public String getIdInternal() {
        return this.internalId;
    }

    @Override
    public long getLastAccessedTime() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return this.session.getMetaData().getLastAccessedTime().toEpochMilli();
        }
    }

    @Override
    public Manager getManager() {
        return this.manager;
    }

    @Override
    public int getMaxInactiveInterval() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return (int) this.session.getMetaData().getMaxInactiveInterval().getSeconds();
        }
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            this.session.getMetaData().setMaxInactiveInterval((interval > 0) ? Duration.ofSeconds(interval) : Duration.ZERO);
        }
    }

    @Override
    public Principal getPrincipal() {
        return this.session.getLocalContext().getPrincipal();
    }

    @Override
    public void setPrincipal(Principal principal) {
        this.session.getLocalContext().setPrincipal(principal);
    }

    @Override
    public HttpSession getSession() {
        return new HttpSessionAdapter(this.session, this.manager, this.batch, this.invalidateAction);
    }

    @Override
    public boolean isValid() {
        return this.session.isValid();
    }

    @Override
    public void addSessionListener(SessionListener listener) {
        this.session.getLocalContext().getSessionListeners().add(listener);
    }

    @Override
    public void endAccess() {
        try {
            if (this.session.isValid()) {
                Batcher<Batch> batcher = this.manager.getSessionManager().getBatcher();
                try (BatchContext context = batcher.resumeBatch(this.batch)) {
                    // If batch was discarded, close it
                    if (this.batch.getState() == Batch.State.DISCARDED) {
                        this.batch.close();
                    }
                    // If batch is closed, close session in a new batch
                    try (Batch batch = (this.batch.getState() == Batch.State.CLOSED) ? batcher.createBatch() : this.batch) {
                        this.session.close();
                    }
                } catch (Throwable e) {
                    this.manager.getContext().getLogger().warn(e.getLocalizedMessage(), e);
                }
            }
        } finally {
            this.closeTask.run();
        }
    }

    @Override
    public void expire() {
        this.session.invalidate();
    }

    @Override
    public Object getNote(String name) {
        return this.session.getLocalContext().getNotes().get(name);
    }

    @Override
    public Iterator<String> getNoteNames() {
        return this.session.getLocalContext().getNotes().keySet().iterator();
    }

    @Override
    public void removeNote(String name) {
        this.session.getLocalContext().getNotes().remove(name);
    }

    @Override
    public void removeSessionListener(SessionListener listener) {
        this.session.getLocalContext().getSessionListeners().remove(listener);
    }

    @Override
    public void setNote(String name, Object value) {
        this.session.getLocalContext().getNotes().put(name, value);
    }

    @Override
    public void tellChangedSessionId(String newId, String oldId) {
        SessionManager<LocalSessionContext, Batch> manager = this.manager.getSessionManager();
        Session<LocalSessionContext> oldSession = this.session;
        try (BatchContext context = manager.getBatcher().resumeBatch(this.batch)) {
            Session<LocalSessionContext> newSession = manager.createSession(newId);
            for (String name: this.session.getAttributes().getAttributeNames()) {
                newSession.getAttributes().setAttribute(name, oldSession.getAttributes().getAttribute(name));
            }
            newSession.getMetaData().setMaxInactiveInterval(oldSession.getMetaData().getMaxInactiveInterval());
            newSession.getMetaData().setLastAccessedTime(oldSession.getMetaData().getLastAccessedTime());
            newSession.getLocalContext().setAuthType(oldSession.getLocalContext().getAuthType());
            newSession.getLocalContext().setPrincipal(oldSession.getLocalContext().getPrincipal());
            this.session = newSession;
            oldSession.invalidate();
        }

        // Invoke listeners outside of the context of the batch associated with this session
        this.manager.getContext().fireContainerEvent(Context.CHANGE_SESSION_ID_EVENT, new String[] { oldId, newId });
    }

    @Override
    public boolean isAttributeDistributable(String name, Object value) {
        return this.manager.getMarshallability().isMarshallable(value);
    }
}
