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
import java.util.function.BiConsumer;
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

    private final Session<LocalSessionContext> session;
    private final CatalinaManager<B> manager;
    private final B batch;
    private final Runnable invalidateAction;

    public HttpSessionAdapter(Session<LocalSessionContext> session, CatalinaManager<B> manager, B batch, Runnable invalidateAction) {
        this.session = session;
        this.manager = manager;
        this.batch = batch;
        this.invalidateAction = invalidateAction;
    }

    @Override
    public boolean isNew() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return this.session.getMetaData().isNew();
        }
    }

    @Override
    public long getCreationTime() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return this.session.getMetaData().getCreationTime().toEpochMilli();
        }
    }

    @Override
    public long getLastAccessedTime() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return this.session.getMetaData().getLastAccessStartTime().toEpochMilli();
        }
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
    public void invalidate() {
        this.invalidateAction.run();
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            this.session.invalidate();
            this.batch.close();
        }
    }

    @Override
    public Object getAttribute(String name) {
        if (EXCLUDED_ATTRIBUTES.contains(name)) {
            return this.session.getLocalContext().getNotes().get(name);
        }
        this.session.getLocalContext().getNotes().get(name);
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return this.session.getAttributes().getAttribute(name);
        }
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return Collections.enumeration(this.session.getAttributes().getAttributeNames());
        }
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value != null) {
            if (EXCLUDED_ATTRIBUTES.contains(name)) {
                this.session.getLocalContext().getNotes().put(name, value);
            } else {
                Object old = null;
                try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
                    old = this.session.getAttributes().setAttribute(name, value);
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
        if (EXCLUDED_ATTRIBUTES.contains(name)) {
            this.session.getLocalContext().getNotes().remove(name);
        } else {
            Object value = null;
            try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
                value = this.session.getAttributes().removeAttribute(name);
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
        return this.session.getId();
    }

    @Override
    public ServletContext getServletContext() {
        return this.manager.getContext().getServletContext();
    }
}
