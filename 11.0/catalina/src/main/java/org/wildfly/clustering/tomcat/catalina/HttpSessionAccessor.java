/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat.catalina;

import jakarta.servlet.http.HttpSession;

import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.SuspendedBatch;
import org.wildfly.clustering.context.Context;
import org.wildfly.clustering.function.Runner;
import org.wildfly.clustering.function.Supplier;
import org.wildfly.clustering.session.Session;

/**
 * Accessor used to access session outside the scope of a request.
 * @author Paul Ferraro
 */
public class HttpSessionAccessor implements HttpSession.Accessor {
	private final CatalinaManager manager;
	private final Supplier<String> identifier;

	/**
	 * Constructs a new session accessor using the specified manager and identifier provider.
	 * @param manager the manager of the session
	 * @param identifier the session identifier provider
	 */
	public HttpSessionAccessor(CatalinaManager manager, Supplier<String> identifier) {
		this.manager = manager;
		this.identifier = identifier;
	}

	@Override
	public void access(java.util.function.Consumer<HttpSession> consumer) {
		try (Batch batch = this.manager.getSessionManager().getBatchFactory().get()) {
			try (Session<CatalinaSessionContext> session = this.manager.getSessionManager().findSession(this.identifier.get())) {
				if (session != null) {
					try (Context<SuspendedBatch> context = batch.suspendWithContext()) {
						consumer.accept(new HttpSessionAdapter(this.manager, Supplier.of(session), context.get(), Runner.empty()));
					}
				}
			} catch (RuntimeException | Error e) {
				batch.discard();
				throw e;
			}
		}
	}

	@Override
	public int hashCode() {
		return this.identifier.get().hashCode();
	}

	@Override
	public boolean equals(Object object) {
		return (object instanceof HttpSessionAccessor accessor) ? this.manager.equals(accessor.manager) && this.identifier.get().equals(accessor.identifier.get()) : false;
	}

	@Override
	public String toString() {
		return this.identifier.get();
	}
}
