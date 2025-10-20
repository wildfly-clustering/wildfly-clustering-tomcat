/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import org.apache.catalina.SessionIdGenerator;
import org.wildfly.clustering.function.Supplier;

/**
 * An identifier factory that uses Tomcat's SessionIdGenerator.
 * @author Paul Ferraro
 */
public class CatalinaIdentifierFactory implements Supplier<String> {

	private final SessionIdGenerator generator;

	/**
	 * Creates an identifier factory.
	 * @param generator a session identifier generator
	 */
	public CatalinaIdentifierFactory(SessionIdGenerator generator) {
		this.generator = generator;
		// Prevent Tomcat's session id generator from auto-appending the route
		this.generator.setJvmRoute(null);
	}

	@Override
	public String get() {
		return this.generator.generateSessionId();
	}
}
