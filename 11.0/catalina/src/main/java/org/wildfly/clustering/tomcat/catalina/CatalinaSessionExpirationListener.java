/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.catalina.Context;
import org.wildfly.clustering.context.ContextClassLoaderReference;
import org.wildfly.clustering.context.ContextualExecutor;
import org.wildfly.clustering.session.ImmutableSession;

/**
 * Invokes following timeout of a session.
 * @author Paul Ferraro
 */
public class CatalinaSessionExpirationListener implements Consumer<ImmutableSession> {

	private final Consumer<ImmutableSession> expireAction;
	private final Executor executor;

	public CatalinaSessionExpirationListener(Context context) {
		this.expireAction = new CatalinaSessionDestroyAction(context);
		this.executor = ContextualExecutor.withContextProvider(ContextClassLoaderReference.INSTANCE.provide(context.getLoader().getClassLoader()));
	}

	@Override
	public void accept(ImmutableSession session) {
		this.executor.execute(() -> this.expireAction.accept(session));
	}
}
