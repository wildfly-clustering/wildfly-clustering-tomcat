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

import java.util.function.Supplier;

import org.apache.catalina.SessionIdGenerator;

/**
 * An identifier factory that uses Tomcat's SessionIdGenerator.
 * @author Paul Ferraro
 */
public class CatalinaIdentifierFactory implements Supplier<String> {

	private final SessionIdGenerator generator;

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
