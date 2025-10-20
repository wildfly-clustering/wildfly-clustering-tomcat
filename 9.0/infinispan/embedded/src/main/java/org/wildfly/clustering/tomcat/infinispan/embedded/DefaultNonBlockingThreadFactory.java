/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.infinispan.embedded;

import org.infinispan.commons.executors.NonBlockingResource;
import org.wildfly.clustering.context.DefaultThreadFactory;

/**
 * Thread factory for non-blocking threads.
 * @author Paul Ferraro
 */
public class DefaultNonBlockingThreadFactory extends DefaultThreadFactory implements NonBlockingResource {
	/**
	 * Creates a factory for creating non-blocking threads.
	 * @param targetClass the class whose loader should be associated with threads created by this factory
	 */
	public DefaultNonBlockingThreadFactory(Class<?> targetClass) {
		super(targetClass, targetClass.getClassLoader());
	}
}
