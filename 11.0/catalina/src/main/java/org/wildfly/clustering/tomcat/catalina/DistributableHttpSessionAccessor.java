/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat.catalina;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpSession;

import org.wildfly.clustering.function.Function;
import org.wildfly.clustering.server.util.Reference;
import org.wildfly.clustering.session.Session;

/**
 * Accessor used to access session outside the scope of a request.
 * @author Paul Ferraro
 */
public class DistributableHttpSessionAccessor implements HttpSession.Accessor {
	private final CatalinaManager manager;
	private final String id;
	private final Reference.Reader<HttpSession> reader;

	/**
	 * Constructs a new session accessor using the specified manager and identifier provider.
	 * @param manager the manager of the session
	 * @param id the session identifier
	 */
	public DistributableHttpSessionAccessor(CatalinaManager manager, String id) {
		this.manager = manager;
		this.id = id;
		this.reader = this.manager.getSessionManager().getSessionReference(this.id).getReader().map(new Function<>() {
			@Override
			public HttpSession apply(Session<CatalinaSessionContext> session) {
				if (session == null) {
					throw new IllegalStateException();
				}
				return new DistributableHttpSession(manager, Reference.of(session), new AtomicReference<>());
			}
		});
	}

	@Override
	public void access(java.util.function.Consumer<HttpSession> consumer) {
		this.reader.read(consumer);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.manager, this.id);
	}

	@Override
	public boolean equals(Object object) {
		return (object instanceof DistributableHttpSessionAccessor accessor) && this.manager.equals(accessor.manager) && this.id.equals(accessor.id);
	}

	@Override
	public String toString() {
		return this.id;
	}
}
