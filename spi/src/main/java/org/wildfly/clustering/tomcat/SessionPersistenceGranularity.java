/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.util.function.Supplier;

import org.wildfly.clustering.session.SessionAttributePersistenceStrategy;

/**
 * Enumerates the supported session persistence granularities.
 * @author Paul Ferraro
 */
public enum SessionPersistenceGranularity implements Supplier<SessionAttributePersistenceStrategy> {
	/** Persists all session attributes, preserving cross attribute object references. */
	SESSION(SessionAttributePersistenceStrategy.COARSE),
	/** Persists modified session attributes only, breaking any cross attribute object references. */
	ATTRIBUTE(SessionAttributePersistenceStrategy.FINE),
	;
	private final SessionAttributePersistenceStrategy strategy;

	SessionPersistenceGranularity(SessionAttributePersistenceStrategy strategy) {
		this.strategy = strategy;
	}

	@Override
	public SessionAttributePersistenceStrategy get() {
		return this.strategy;
	}
}
