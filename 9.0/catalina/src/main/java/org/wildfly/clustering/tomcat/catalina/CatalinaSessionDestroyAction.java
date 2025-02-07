/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;

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
		HttpSessionEvent event = new HttpSessionEvent(HttpSessionProvider.INSTANCE.asSession(session, this.context.getServletContext()));
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
		for (Map.Entry<String, Object> entry : session.getAttributes().entrySet()) {
			if (entry.getValue() instanceof HttpSessionBindingListener) {
				HttpSessionBindingListener listener = (HttpSessionBindingListener) entry.getValue();
				try {
					listener.valueUnbound(new HttpSessionBindingEvent(event.getSession(), entry.getKey(), listener));
				} catch (Throwable e) {
					this.context.getLogger().warn(e.getMessage(), e);
				}
			}
		}
	}
}
