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

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.web.session.Session;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;

/**
 * Adapts a WildFly distributable Session to an HttpSession.
 * @author Paul Ferraro
 */
public class HttpSessionAdapter<B extends Batch> extends AbstractHttpSession {

    private static final Set<String> EXCLUDED_ATTRIBUTES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Globals.SUBJECT_ATTR, Globals.GSS_CREDENTIAL_ATTR, org.apache.catalina.valves.CrawlerSessionManagerValve.class.getName())));

    enum AttributeEventType implements BiConsumer<Context, HttpSessionBindingEvent> {
        ADDED("beforeSessionAttributeAdded", "afterSessionAttributeAdded", (listener, event) -> listener.attributeAdded(event)),
        REMOVED("beforeSessionAttributeRemoved", "afterSessionAttributeRemoved", (listener, event) -> listener.attributeRemoved(event)),
        REPLACED("beforeSessionAttributeReplaced", "afterSessionAttributeReplaced", (listener, event) -> listener.attributeReplaced(event)),
        ;
        private final String beforeEvent;
        private final String afterEvent;
        private final BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> trigger;

        AttributeEventType(String beforeEvent, String afterEvent, BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> trigger) {
            this.beforeEvent = beforeEvent;
            this.afterEvent = afterEvent;
            this.trigger = trigger;
        }

        @Override
        public void accept(Context context, HttpSessionBindingEvent event) {
            Stream.of(context.getApplicationEventListeners()).filter(HttpSessionAttributeListener.class::isInstance).map(HttpSessionAttributeListener.class::cast).forEach(listener -> {
                try {
                    context.fireContainerEvent(this.beforeEvent, listener);
                    this.trigger.accept(listener, event);
                } catch (Throwable e) {
                    context.getLogger().warn(e.getMessage(), e);
                } finally {
                    context.fireContainerEvent(this.afterEvent, listener);
                }
            });
        }
    }

    private final AtomicReference<Session<LocalSessionContext>> session;
    private final CatalinaManager<B> manager;
    private final B batch;
    private final Runnable invalidateAction;
    private final Consumer<Session<LocalSessionContext>> closeIfInvalid;

    public HttpSessionAdapter(AtomicReference<Session<LocalSessionContext>> session, CatalinaManager<B> manager, B batch, Runnable invalidateAction, Consumer<Session<LocalSessionContext>> closeIfInvalid) {
        this.session = session;
        this.manager = manager;
        this.batch = batch;
        this.invalidateAction = invalidateAction;
        this.closeIfInvalid = closeIfInvalid;
    }

    @Override
    public boolean isNew() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return session.getMetaData().isNew();
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public long getCreationTime() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return session.getMetaData().getCreationTime().toEpochMilli();
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public long getLastAccessedTime() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return session.getMetaData().getLastAccessStartTime().toEpochMilli();
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public int getMaxInactiveInterval() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return (int) session.getMetaData().getMaxInactiveInterval().getSeconds();
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            session.getMetaData().setMaxInactiveInterval((interval > 0) ? Duration.ofSeconds(interval) : Duration.ZERO);
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public void invalidate() {
        this.invalidateAction.run();
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            session.invalidate();
            this.batch.close();
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public Object getAttribute(String name) {
        Session<LocalSessionContext> session = this.session.get();
        if (EXCLUDED_ATTRIBUTES.contains(name)) {
            return session.getLocalContext().getNotes().get(name);
        }
        session.getLocalContext().getNotes().get(name);
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return session.getAttributes().getAttribute(name);
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        Session<LocalSessionContext> session = this.session.get();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return Collections.enumeration(session.getAttributes().getAttributeNames());
        } catch (IllegalStateException e) {
            this.closeIfInvalid.accept(session);
            throw e;
        }
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value != null) {
            Session<LocalSessionContext> session = this.session.get();
            if (EXCLUDED_ATTRIBUTES.contains(name)) {
                session.getLocalContext().getNotes().put(name, value);
            } else {
                Object old = null;
                try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
                    old = session.getAttributes().setAttribute(name, value);
                } catch (IllegalStateException e) {
                    this.closeIfInvalid.accept(session);
                    throw e;
                }
                if (old != value) {
                    this.notifySessionAttributeListeners(name, old, value);
                }
            }
        } else {
            this.removeAttribute(name);
        }
    }

    @Override
    public void removeAttribute(String name) {
        Session<LocalSessionContext> session = this.session.get();
        if (EXCLUDED_ATTRIBUTES.contains(name)) {
            session.getLocalContext().getNotes().remove(name);
        } else {
            Object value = null;
            try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
                value = session.getAttributes().removeAttribute(name);
            } catch (IllegalStateException e) {
                this.closeIfInvalid.accept(session);
                throw e;
            }
            if (value != null) {
                this.notifySessionAttributeListeners(name, value, null);
            }
        }
    }

    private void notifySessionAttributeListeners(String name, Object oldValue, Object newValue) {
        if (oldValue instanceof HttpSessionBindingListener) {
            HttpSessionBindingListener listener = (HttpSessionBindingListener) oldValue;
            try {
                listener.valueUnbound(new HttpSessionBindingEvent(this, name));
            } catch (Throwable e) {
                this.manager.getContext().getLogger().warn(e.getMessage(), e);
            }
        }
        if (newValue instanceof HttpSessionBindingListener) {
            HttpSessionBindingListener listener = (HttpSessionBindingListener) newValue;
            try {
                listener.valueBound(new HttpSessionBindingEvent(this, name));
            } catch (Throwable e) {
                this.manager.getContext().getLogger().warn(e.getMessage(), e);
            }
        }
        HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, (oldValue != null) ? oldValue : newValue);
        AttributeEventType type = (oldValue == null) ? AttributeEventType.ADDED : (newValue == null) ? AttributeEventType.REMOVED : AttributeEventType.REPLACED;
        type.accept(this.manager.getContext(), event);
    }

    @Override
    public String getId() {
        return this.session.get().getId();
    }

    @Override
    public ServletContext getServletContext() {
        return this.manager.getContext().getServletContext();
    }
}
