/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat;

import java.util.EnumSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

/**
 * Provider of session management arguments.
 * @author Paul Ferraro
 */
public class SessionManagementArgumentsProvider implements ArgumentsProvider {

	@Override
	public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
		Stream.Builder<Arguments> builder = Stream.builder();
		for (SessionPersistenceGranularity strategy : EnumSet.allOf(SessionPersistenceGranularity.class)) {
			for (SessionMarshallerFactory marshaller : EnumSet.allOf(SessionMarshallerFactory.class)) {
				builder.add(Arguments.of(new SessionManagementArguments() {
					@Override
					public SessionPersistenceGranularity getSessionPersistenceGranularity() {
						return strategy;
					}

					@Override
					public SessionMarshallerFactory getSessionMarshallerFactory() {
						return marshaller;
					}

					@Override
					public String toString() {
						return String.join("-", strategy.name(), marshaller.name());
					}
				}));
			}
		}
		return builder.build();
	}
}
