/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;

import org.apache.catalina.Globals;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.Function;
import org.wildfly.clustering.function.Supplier;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.server.util.Reference;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.ImmutableSessionMetaData;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.SessionMetaData;

/**
 * Adapts a WildFly distributable Session to an HttpSession.
 * @author Paul Ferraro
 */
public class DistributableHttpSession extends AbstractHttpSession {
	static final UnaryOperator<Session<CatalinaSessionContext>> REQUIRE_VALID = UnaryOperator.when(ImmutableSession.VALID, UnaryOperator.identity(), UnaryOperator.of(Consumer.<Session<CatalinaSessionContext>>of().thenThrow(IllegalStateException::new), Supplier.of(null)));
	static final Function<Session<CatalinaSessionContext>, CatalinaSessionContext> CONTEXT = REQUIRE_VALID.thenApply(Session::getContext);
	static final Function<Session<CatalinaSessionContext>, Map<String, Object>> NOTES = CONTEXT.thenApply(CatalinaSessionContext::getNotes);
	private static final Set<String> EXCLUDED_ATTRIBUTES = Set.of(Globals.GSS_CREDENTIAL_ATTR, org.apache.catalina.valves.CrawlerSessionManagerValve.class.getName());

	private final CatalinaManager manager;
	private final Reference.Reader<Session<CatalinaSessionContext>> sessionReader;
	private final Reference.Reader<SessionMetaData> sessionMetaDataReader;
	private final Reference.Reader<Map<String, Object>> sessionAttributesReader;
	private final Reference.Reader<Map<String, Object>> sessionNotesReader;
	private final AtomicReference<Runnable> invalidateTask;

	/**
	 * Creates a session adapter.
	 * @param manager the manager of this session
	 * @param reference a reference to the session
	 * @param invalidateTask a task to run on session invalidation
	 */
	public DistributableHttpSession(CatalinaManager manager, Reference<Session<CatalinaSessionContext>> reference, AtomicReference<Runnable> invalidateTask) {
		this.manager = manager;
		this.sessionReader = reference.getReader();
		this.sessionMetaDataReader = this.sessionReader.map(Session.METADATA);
		this.sessionAttributesReader = this.sessionReader.map(Session.ATTRIBUTES);
		this.sessionNotesReader = this.sessionReader.map(NOTES);
		this.invalidateTask = invalidateTask;
	}

	@Override
	public String getId() {
		return this.sessionReader.map(ImmutableSession.IDENTIFIER).get();
	}

	@Override
	public ServletContext getServletContext() {
		return this.manager.getContext().getServletContext();
	}

	@Override
	public boolean isNew() {
		return this.sessionMetaDataReader.map(ImmutableSessionMetaData.LAST_ACCESS_START_TIME).get().isEmpty();
	}

	@Override
	public long getCreationTime() {
		return this.sessionMetaDataReader.map(ImmutableSessionMetaData.CREATION_TIME).get().toEpochMilli();
	}

	@Override
	public long getLastAccessedTime() {
		return this.sessionMetaDataReader.map(ImmutableSessionMetaData.LAST_ACCESS_TIME).get().toEpochMilli();
	}

	@Override
	public int getMaxInactiveInterval() {
		return (int) this.sessionMetaDataReader.map(ImmutableSessionMetaData.MAX_IDLE).get().orElse(Duration.ZERO).getSeconds();
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		Duration maxIdle = interval > 0 ? Duration.ofSeconds(interval) : Duration.ZERO;
		this.sessionMetaDataReader.read(SessionMetaData.MAX_IDLE.composeUnary(Function.identity(), Function.of(maxIdle)));
	}

	@Override
	public void invalidate() {
		Runnable invalidateTask = this.invalidateTask.getAndSet(null);
		try {
			this.sessionReader.read(REQUIRE_VALID.thenAccept(validSession -> {
				// Tomcat does not guarantee that Session.endAccess() will be triggered for invalidated sessions
				try (Session<CatalinaSessionContext> session = validSession) {
					CatalinaSessionEventNotifier.Lifecycle.DESTROY.accept(this.manager, new HttpSessionEvent(this));
					session.invalidate();
				}
			}));
		} finally {
			if (invalidateTask != null) {
				invalidateTask.run();
			}
		}
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		return Collections.enumeration(this.sessionAttributesReader.map(ImmutableSession.ATTRIBUTE_NAMES).get());
	}

	private Reference.Reader<Map<String, Object>> getAttributeReader(String name) {
		return EXCLUDED_ATTRIBUTES.contains(name) ? this.sessionNotesReader : this.sessionAttributesReader;
	}

	@Override
	public Object getAttribute(String name) {
		Reference.Reader<Map<String, Object>> reader = this.getAttributeReader(name);
		return reader.map(ImmutableSession.GET_ATTRIBUTE.composeUnary(Function.identity(), Function.of(name))).get();
	}

	@Override
	public void setAttribute(String name, Object value) {
		if (value != null) {
			Reference.Reader<Map<String, Object>> reader = this.getAttributeReader(name);
			Object old = reader.map(attributes -> attributes.put(name, value)).get();
			if ((reader == this.sessionAttributesReader) && (old != value)) {
				this.notifySessionAttributeListeners(name, old, value);
			}
		} else {
			this.removeAttribute(name);
		}
	}

	@Override
	public void removeAttribute(String name) {
		Reference.Reader<Map<String, Object>> reader = this.getAttributeReader(name);
		Object value = reader.map(Session.REMOVE_ATTRIBUTE.composeUnary(Function.identity(), Function.of(name))).get();
		if ((reader == this.sessionAttributesReader) && (value != null)) {
			this.notifySessionAttributeListeners(name, value, null);
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
	public Accessor getAccessor() {
		return new DistributableHttpSessionAccessor(this.manager, this.getId());
	}
}
