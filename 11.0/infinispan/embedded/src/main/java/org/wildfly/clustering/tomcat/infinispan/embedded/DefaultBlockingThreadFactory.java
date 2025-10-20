/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.infinispan.embedded;

import org.wildfly.clustering.context.DefaultThreadFactory;

/**
 * Thread factory for non-blocking threads.
 * @author Paul Ferraro
 */
public class DefaultBlockingThreadFactory extends DefaultThreadFactory {
	/**
	 * Creates a factory for creating blocking threads.
	 * @param targetClass the class whose loader should be associated with threads created by this factory
	 */
	public DefaultBlockingThreadFactory(Class<?> targetClass) {
		super(targetClass, targetClass.getClassLoader());
	}
}
