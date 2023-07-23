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

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.catalina.Context;
import org.wildfly.clustering.context.ContextClassLoaderReference;
import org.wildfly.clustering.context.ContextReferenceExecutor;
import org.wildfly.clustering.web.session.ImmutableSession;

/**
 * Invokes following timeout of a session.
 * @author Paul Ferraro
 */
public class CatalinaSessionExpirationListener implements Consumer<ImmutableSession> {

	private final Consumer<ImmutableSession> expireAction;
	private final Executor executor;

	public CatalinaSessionExpirationListener(Context context) {
		this.expireAction = new CatalinaSessionDestroyAction(context);
		this.executor = new ContextReferenceExecutor<>(context.getLoader().getClassLoader(), ContextClassLoaderReference.INSTANCE);
	}

	@Override
	public void accept(ImmutableSession session) {
		this.executor.execute(() -> this.expireAction.accept(session));
	}
}
