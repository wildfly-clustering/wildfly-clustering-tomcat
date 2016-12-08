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

package org.wildfly.clustering.tomcat.catalina.session;

import java.time.Duration;
import java.util.Enumeration;
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.catalina.session.Constants;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.web.session.ImmutableHttpSessionAdapter;
import org.wildfly.clustering.web.session.Session;

/**
 * Adapts a WildFly distributable Session to an HttpSession.
 * @author Paul Ferraro
 */
public class HttpSessionAdapter extends ImmutableHttpSessionAdapter {

    private final Session<LocalSessionContext> session;
    private final TomcatManager manager;
    private final Batch batch;
    private final Runnable invalidateAction;

    public HttpSessionAdapter(Session<LocalSessionContext> session, TomcatManager manager, Batch batch, Runnable invalidateAction) {
        super(session, manager.getContext().getServletContext());
        this.session = session;
        this.manager = manager;
        this.batch = batch;
        this.invalidateAction = invalidateAction;
    }

    @Override
    public boolean isNew() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return super.isNew();
        }
    }

    @Override
    public long getCreationTime() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return super.getCreationTime();
        }
    }

    @Override
    public long getLastAccessedTime() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return super.getLastAccessedTime();
        }
    }

    @Override
    public int getMaxInactiveInterval() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return super.getMaxInactiveInterval();
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
        if (Constants.excludedAttributeNames.contains(name)) {
            return this.session.getLocalContext().getNotes().get(name);
        }
        this.session.getLocalContext().getNotes().get(name);
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return super.getAttribute(name);
        }
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        try (BatchContext context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
            return super.getAttributeNames();
        }
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value != null) {
            if (Constants.excludedAttributeNames.contains(name)) {
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
        if (Constants.excludedAttributeNames.contains(name)) {
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
        Stream.of(this.manager.getContext().getApplicationEventListeners()).filter(listener -> listener instanceof HttpSessionAttributeListener).map(listener -> (HttpSessionAttributeListener) listener).forEach(listener -> {
            try {
                if (oldValue == null) {
                    listener.attributeAdded(event);
                } else if (newValue == null) {
                    listener.attributeRemoved(event);
                } else {
                    listener.attributeReplaced(event);
                }
            } catch (Throwable e) {
                this.manager.getContext().getLogger().warn(e.getMessage(), e);
            }
        });
    }
}
