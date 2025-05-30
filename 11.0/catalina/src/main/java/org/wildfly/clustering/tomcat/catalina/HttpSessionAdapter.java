/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;

import org.apache.catalina.Globals;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.SuspendedBatch;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;

/**
 * Adapts a WildFly distributable Session to an HttpSession.
 * @author Paul Ferraro
 */
public class HttpSessionAdapter extends AbstractHttpSession {

	private static final Set<String> EXCLUDED_ATTRIBUTES = Set.of(Globals.GSS_CREDENTIAL_ATTR, org.apache.catalina.valves.CrawlerSessionManagerValve.class.getName());

	private final AtomicReference<Session<CatalinaSessionContext>> session;
	private final CatalinaManager manager;
	private final SuspendedBatch batch;
	private final Runnable closeTask;

	public HttpSessionAdapter(AtomicReference<Session<CatalinaSessionContext>> session, CatalinaManager manager, SuspendedBatch batch, Runnable closeTask) {
		this.session = session;
		this.manager = manager;
		this.batch = batch;
		this.closeTask = closeTask;
	}

	@Override
	public boolean isNew() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return session.getMetaData().isNew();
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
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
	public void invalidate() {
		HttpSessionEvent event = new HttpSessionEvent(HttpSessionProvider.INSTANCE.asSession(this.session.get(), this.manager.getContext().getServletContext()));
		CatalinaSessionEventNotifier.Lifecycle.DESTROY.accept(this.manager, event);
		try (Batch batch = this.batch.resume()) {
			try (Session<CatalinaSessionContext> session = this.session.get()) {
				session.invalidate();
			}
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			Session<CatalinaSessionContext> session = this.session.get();
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		} finally {
			this.closeTask.run();
		}
	}

	@Override
	public Object getAttribute(String name) {
		Session<CatalinaSessionContext> session = this.session.get();
		if (EXCLUDED_ATTRIBUTES.contains(name)) {
			return session.getContext().getNotes().get(name);
		}
		try {
			return session.getAttributes().get(name);
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return Collections.enumeration(session.getAttributes().keySet());
		} catch (IllegalStateException e) {
			// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
			if (!session.isValid()) {
				session.close();
			}
			throw e;
		}
	}

	@Override
	public void setAttribute(String name, Object value) {
		if (value != null) {
			Session<CatalinaSessionContext> session = this.session.get();
			if (EXCLUDED_ATTRIBUTES.contains(name)) {
				session.getContext().getNotes().put(name, value);
			} else {
				try {
					Object old = session.getAttributes().put(name, value);
					if (old != value) {
						this.notifySessionAttributeListeners(name, old, value);
					}
				} catch (IllegalStateException e) {
					// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
					if (!session.isValid()) {
						session.close();
					}
					throw e;
				}
			}
		} else {
			this.removeAttribute(name);
		}
	}

	@Override
	public void removeAttribute(String name) {
		Session<CatalinaSessionContext> session = this.session.get();
		if (EXCLUDED_ATTRIBUTES.contains(name)) {
			session.getContext().getNotes().remove(name);
		} else {
			try {
				Object value = session.getAttributes().remove(name);
				if (value != null) {
					this.notifySessionAttributeListeners(name, value, null);
				}
			} catch (IllegalStateException e) {
				// If session was invalidated by a concurrent request, Tomcat may not trigger Session.endAccess(), so we need to close the session here
				if (!session.isValid()) {
					session.close();
				}
				throw e;
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
		CatalinaSessionEventNotifier.Attribute notifier = (oldValue == null) ? CatalinaSessionEventNotifier.Attribute.ADDED : (newValue == null) ? CatalinaSessionEventNotifier.Attribute.REMOVED : CatalinaSessionEventNotifier.Attribute.REPLACED;
		notifier.accept(this.manager, event);
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
