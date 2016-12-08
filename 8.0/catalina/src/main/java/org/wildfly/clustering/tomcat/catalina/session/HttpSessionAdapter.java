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
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.catalina.Context;
import org.wildfly.clustering.web.session.ImmutableHttpSessionAdapter;
import org.wildfly.clustering.web.session.Session;

/**
 * Adapts a WildFly distributable Session to an HttpSession.
 * @author Paul Ferraro
 */
public class HttpSessionAdapter extends ImmutableHttpSessionAdapter {

    private final Session<?> session;
    private final Context context;
    private final Runnable invalidateAction;

    public HttpSessionAdapter(Session<?> session, Context context, Runnable invalidateAction) {
        super(session, context.getServletContext());
        this.session = session;
        this.context = context;
        this.invalidateAction = invalidateAction;
    }

    @Override
    public void invalidate() {
        this.invalidateAction.run();
        this.session.invalidate();
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        this.session.getMetaData().setMaxInactiveInterval((interval > 0) ? Duration.ofSeconds(interval) : Duration.ZERO);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            this.removeAttribute(name);
            return;
        }
        Object old = this.session.getAttributes().setAttribute(name, value);
        if (old != value) {
            if (value instanceof HttpSessionBindingListener) {
                HttpSessionBindingListener listener = (HttpSessionBindingListener) value;
                try {
                    listener.valueBound(new HttpSessionBindingEvent(this, name));
                } catch (Throwable e) {
                    this.context.getLogger().warn(e.getMessage(), e);
                }
            }
            if (old instanceof HttpSessionBindingListener) {
                HttpSessionBindingListener listener = (HttpSessionBindingListener) old;
                try {
                    listener.valueUnbound(new HttpSessionBindingEvent(this, name));
                } catch (Throwable e) {
                    this.context.getLogger().warn(e.getMessage(), e);
                }
            }
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, (old != null) ? old : value);
            Stream.of(this.context.getApplicationEventListeners()).filter(listener -> listener instanceof HttpSessionAttributeListener).map(listener -> (HttpSessionAttributeListener) listener).forEach(listener -> {
                try {
                    if (old == null) {
                        listener.attributeAdded(event);
                    } else {
                        listener.attributeReplaced(event);
                    }
                } catch (Throwable e) {
                    this.context.getLogger().warn(e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void removeAttribute(String name) {
        Object value = this.session.getAttributes().removeAttribute(name);

        if (value != null) {
            if (value instanceof HttpSessionBindingListener) {
                HttpSessionBindingListener listener = (HttpSessionBindingListener) value;
                listener.valueUnbound(new HttpSessionBindingEvent(this, name));
            }
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, value);
            Stream.of(this.context.getApplicationEventListeners()).filter(listener -> listener instanceof HttpSessionAttributeListener).map(listener -> (HttpSessionAttributeListener) listener).forEach(listener -> {
                try {
                    listener.attributeRemoved(event);
                } catch (Throwable e) {
                    this.context.getLogger().warn(e.getMessage(), e);
                }
            });
        }
    }
}
