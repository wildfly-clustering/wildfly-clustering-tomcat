/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Stream;

import org.infinispan.client.hotrod.DefaultTemplate;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.wildfly.clustering.cache.ContainerProvider;
import org.wildfly.clustering.cache.infinispan.remote.InfinispanServerContainer;
import org.wildfly.clustering.cache.infinispan.remote.InfinispanServerExtension;
import org.wildfly.clustering.session.container.AbstractSessionManagerITCase;

/**
 * @author Paul Ferraro
 */
public abstract class AbstractHotRodSessionManagerITCase extends AbstractSessionManagerITCase {

	private static final String CONTEXT_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<Context>\n"
			+ "\t<Manager className=\"%s\" granularity=\"%s\" marshaller=\"%s\" template=\"%s\" uri=\"hotrod://%s:%s@%s:%s?client_intelligence=%s\" tcp_no_delay=\"true\"/>\n"
			+ "</Context>\n";

	@RegisterExtension
	static final ContainerProvider<InfinispanServerContainer> INFINISPAN = new InfinispanServerExtension();

	private final Class<?> managerClass;

	protected AbstractHotRodSessionManagerITCase(Class<?> managerClass) {
		this.managerClass = managerClass;
	}

	@ParameterizedTest(name = ParameterizedTest.ARGUMENTS_PLACEHOLDER)
	@ArgumentsSource(HotRodSessionManagerArgumentsProvider.class)
	@RunAsClient
	public void test(SessionManagementParameters parameters) throws Exception {
		InfinispanServerContainer container = INFINISPAN.getContainer();
		Object[] values = new Object[] {
				this.managerClass.getName(),
				parameters.getSessionPersistenceGranularity(),
				parameters.getSessionMarshallerFactory(),
				DefaultTemplate.LOCAL.getTemplateName(),
				container.getUsername(),
				String.valueOf(container.getPassword()),
				container.getHost(),
				container.getPort(),
				// TODO Figure out how to configure HASH_DISTRIBUTION_AWARE w/bridge networking
				container.isPortMapping() ? ClientIntelligence.BASIC : ClientIntelligence.HASH_DISTRIBUTION_AWARE,
		};
		WebArchive archive = this.get().addAsManifestResource(new StringAsset(String.format(CONTEXT_XML, values)), "context.xml");
		this.accept(archive);
	}

	public static class HotRodSessionManagerArgumentsProvider implements ArgumentsProvider {
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
