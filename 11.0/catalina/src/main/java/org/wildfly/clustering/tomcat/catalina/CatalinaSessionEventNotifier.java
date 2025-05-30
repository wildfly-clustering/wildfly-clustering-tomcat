/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat.catalina;

import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.apache.catalina.Manager;

/**
 * Describes a container session event.
 * @author Paul Ferraro
 */
public interface CatalinaSessionEventNotifier<L, E extends HttpSessionEvent> extends BiConsumer<Manager, E> {
	Class<L> getListenerClass();
	String getEventType();
	BiConsumer<L, E> getEventNotifier();

	@Override
	default void accept(Manager manager, E event) {
		Class<L> listenerClass = this.getListenerClass();
		String eventType = this.getEventType();
		Context context = manager.getContext();
		Stream.of(context.getApplicationEventListeners()).filter(listenerClass::isInstance).map(listenerClass::cast).forEach(listener -> {
			try {
				context.fireContainerEvent("beforeSession" + eventType, listener);
				this.getEventNotifier().accept(listener, event);
			} catch (Throwable e) {
				context.getLogger().warn(e.getMessage(), e);
			} finally {
				context.fireContainerEvent("afterSession" + eventType, listener);
			}
		});
	}

	enum Lifecycle implements CatalinaSessionEventNotifier<HttpSessionListener, HttpSessionEvent> {
		CREATE("Created", HttpSessionListener::sessionCreated),
		DESTROY("Destroyed", HttpSessionListener::sessionDestroyed) {
			@Override
			public void accept(Manager manager, HttpSessionEvent event) {
				super.accept(manager, event);
				// Also trigger unbound events
				HttpSession session = event.getSession();
				Iterator<String> names = session.getAttributeNames().asIterator();
				while (names.hasNext()) {
					String name = names.next();
					Object value = session.getAttribute(name);
					if (value instanceof HttpSessionBindingListener) {
						HttpSessionBindingListener listener = (HttpSessionBindingListener) value;
						try {
							listener.valueUnbound(new HttpSessionBindingEvent(session, name, listener));
						} catch (Throwable e) {
							manager.getContext().getLogger().warn(e.getMessage(), e);
						}
					}
				}
			}
		}
		;
		private final String eventType;
		private final BiConsumer<HttpSessionListener, HttpSessionEvent> notifier;

		Lifecycle(String eventType, BiConsumer<HttpSessionListener, HttpSessionEvent> notifier) {
			this.eventType = eventType;
			this.notifier = notifier;
		}

		@Override
		public Class<HttpSessionListener> getListenerClass() {
			return HttpSessionListener.class;
		}

		@Override
		public String getEventType() {
			return this.eventType;
		}

		@Override
		public BiConsumer<HttpSessionListener, HttpSessionEvent> getEventNotifier() {
			return this.notifier;
		}
	}

	enum Attribute implements CatalinaSessionEventNotifier<HttpSessionAttributeListener, HttpSessionBindingEvent> {
		ADDED("AttributeAdded", HttpSessionAttributeListener::attributeAdded),
		REMOVED("AttributeRemoved", HttpSessionAttributeListener::attributeRemoved),
		REPLACED("AttributeReplaced", HttpSessionAttributeListener::attributeReplaced),
		;
		private final String eventType;
		private final BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> notifier;

		Attribute(String eventType, BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> notifier) {
			this.eventType = eventType;
			this.notifier = notifier;
		}

		@Override
		public Class<HttpSessionAttributeListener> getListenerClass() {
			return HttpSessionAttributeListener.class;
		}

		@Override
		public String getEventType() {
			return this.eventType;
		}

		@Override
		public BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> getEventNotifier() {
			return this.notifier;
		}
	}
}
