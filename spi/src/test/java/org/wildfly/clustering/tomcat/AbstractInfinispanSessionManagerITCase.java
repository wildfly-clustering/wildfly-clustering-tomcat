/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Stream;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.wildfly.clustering.session.container.AbstractSessionManagerITCase;
import org.wildfly.clustering.session.container.SessionManagementTesterConfiguration;

/**
 * @author Paul Ferraro
 */
public abstract class AbstractInfinispanSessionManagerITCase extends AbstractSessionManagerITCase<WebArchive> {

	private static final String CONTEXT_XML = """
			<Context>
				<Manager className="%s" granularity="%s" marshaller="%s"/>
			</Context>
	""";

	private final Class<?> managerClass;

	protected AbstractInfinispanSessionManagerITCase(Class<?> managerClass, Class<?> endpointClass) {
		super(new SessionManagementTesterConfiguration() {
			@Override
			public Class<?> getEndpointClass() {
				return endpointClass;
			}
		}, WebArchive.class);
		this.managerClass = managerClass;
	}

	private SessionManagementParameters parameters;

	@ParameterizedTest(name = ParameterizedTest.ARGUMENTS_PLACEHOLDER)
	@ArgumentsSource(InfinispanSessionManagerArgumentsProvider.class)
	@RunAsClient
	public void test(SessionManagementParameters parameters) throws Exception {
		this.parameters = parameters;
		this.run();
	}

	@Override
	public WebArchive createArchive(SessionManagementTesterConfiguration configuration) {
		Object[] values = new Object[] {
				this.managerClass.getName(),
				this.parameters.getSessionPersistenceGranularity(),
				this.parameters.getSessionMarshallerFactory(),
		};
		return super.createArchive(configuration)
				.addAsResource("infinispan.xml")
				.addAsManifestResource(new StringAsset(String.format(CONTEXT_XML, values)), "context.xml");
	}

	public static class InfinispanSessionManagerArgumentsProvider implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			Stream.Builder<Arguments> builder = Stream.builder();
			for (SessionPersistenceGranularity strategy : EnumSet.allOf(SessionPersistenceGranularity.class)) {
				for (SessionMarshallerFactory marshaller : EnumSet.allOf(SessionMarshallerFactory.class)) {
					builder.add(Arguments.of(new SessionManagementParameters() {
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
							return Map.of(SessionPersistenceGranularity.class.getSimpleName(), strategy, SessionMarshallerFactory.class.getSimpleName(), marshaller).toString();
						}
					}));
				}
			}
			return builder.build();
		}
	}

}
