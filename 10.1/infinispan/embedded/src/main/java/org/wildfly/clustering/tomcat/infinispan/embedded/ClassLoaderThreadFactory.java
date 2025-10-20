/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.infinispan.embedded;

import org.jgroups.util.ThreadFactory;
import org.wildfly.clustering.context.Contextualizer;
import org.wildfly.clustering.context.ThreadContextClassLoaderReference;

/**
 * A thread factory decorator that creates contextualized threads.
 * @author Paul Ferraro
 */
public class ClassLoaderThreadFactory implements org.jgroups.util.ThreadFactory {
	private final ThreadFactory factory;
	private final Contextualizer contextualizer;

	/**
	 * Creates a thread factory decorator.
	 * @param factory the decorated thread factory
	 * @param loader the class loader to associated with the context of threads created by this factory
	 */
	public ClassLoaderThreadFactory(ThreadFactory factory, ClassLoader loader) {
		this.factory = factory;
		this.contextualizer = Contextualizer.withContextProvider(ThreadContextClassLoaderReference.CURRENT.provide(loader));
	}

	@Override
	public Thread newThread(Runnable runner) {
		return this.newThread(runner, null);
	}

	@Override
	public Thread newThread(final Runnable runner, String name) {
		return this.factory.newThread(this.contextualizer.contextualize(runner), name);
	}

	@Override
	public void setPattern(String pattern) {
		this.factory.setPattern(pattern);
	}

	@Override
	public void setIncludeClusterName(boolean includeClusterName) {
		this.factory.setIncludeClusterName(includeClusterName);
	}

	@Override
	public void setClusterName(String channelName) {
		this.factory.setClusterName(channelName);
	}

	@Override
	public void setAddress(String address) {
		this.factory.setAddress(address);
	}

	@Override
	public void renameThread(String base_name, Thread thread) {
		this.factory.renameThread(base_name, thread);
	}

	@Override
	public boolean useVirtualThreads() {
		return this.factory.useVirtualThreads();
	}
}
