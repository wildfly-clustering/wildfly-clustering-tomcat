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

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.catalina.Context;
import org.wildfly.clustering.web.cache.session.ImmutableSessionAttributesFilter;
import org.wildfly.clustering.web.cache.session.SessionAttributesFilter;
import org.wildfly.clustering.web.session.ImmutableSession;

import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * Defines an action to be performed prior to destruction of a session.
 * @author Paul Ferraro
 */
public class CatalinaSessionDestroyAction implements Consumer<ImmutableSession> {
	private final Context context;

	public CatalinaSessionDestroyAction(Context context) {
		this.context = context;
	}

	@Override
	public void accept(ImmutableSession session) {
		SessionAttributesFilter filter = new ImmutableSessionAttributesFilter(session);
		HttpSessionEvent event = new HttpSessionEvent(CatalinaSpecificationProvider.INSTANCE.createHttpSession(session, this.context.getServletContext()));
		Stream.of(this.context.getApplicationLifecycleListeners()).filter(HttpSessionListener.class::isInstance).map(HttpSessionListener.class::cast).forEach(listener -> {
			try {
				this.context.fireContainerEvent("beforeSessionDestroyed", listener);
				listener.sessionDestroyed(event);
			} catch (Throwable e) {
				this.context.getLogger().warn(e.getMessage(), e);
			} finally {
				this.context.fireContainerEvent("afterSessionDestroyed", listener);
			}
		});
		for (Map.Entry<String, HttpSessionBindingListener> entry : filter.getAttributes(HttpSessionBindingListener.class).entrySet()) {
			HttpSessionBindingListener listener = entry.getValue();
			try {
				listener.valueUnbound(new HttpSessionBindingEvent(event.getSession(), entry.getKey(), listener));
			} catch (Throwable e) {
				this.context.getLogger().warn(e.getMessage(), e);
			}
		}
	}
}
