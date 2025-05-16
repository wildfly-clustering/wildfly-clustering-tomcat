/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

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
import org.wildfly.clustering.session.container.SessionManagementTesterConfiguration;

/**
 * @author Paul Ferraro
 */
public abstract class AbstractHotRodSessionManagerITCase extends AbstractSessionManagerITCase<WebArchive> {

	@RegisterExtension
	static final ContainerProvider<InfinispanServerContainer> INFINISPAN = new InfinispanServerExtension();

	private final Class<?> managerClass;

	protected AbstractHotRodSessionManagerITCase(Class<?> managerClass, Class<?> endpointClass) {
		super(new SessionManagementTesterConfiguration() {
			@Override
			public Class<?> getEndpointClass() {
				return endpointClass;
			}

			@Override
			public Optional<Duration> getFailoverGracePeriod() {
				return Optional.of(Duration.ofSeconds(2));
			}
		}, WebArchive.class);
		this.managerClass = managerClass;
	}

	private SessionManagementParameters parameters;

	@ParameterizedTest(name = ParameterizedTest.ARGUMENTS_PLACEHOLDER)
	@ArgumentsSource(HotRodSessionManagerArgumentsProvider.class)
	@RunAsClient
	public void test(SessionManagementParameters parameters) throws Exception {
		this.parameters = parameters;
		this.run();
	}

	@Override
	public WebArchive createArchive(SessionManagementTesterConfiguration configuration) {
		InfinispanServerContainer container = INFINISPAN.getContainer();
		XMLOutputFactory factory = XMLOutputFactory.newFactory();
		StringWriter stringWriter = new StringWriter();
		try {
			XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);
			writer.writeStartDocument(StandardCharsets.UTF_8.displayName(), "1.0");
			writer.writeStartElement("Context");
			{
				writer.writeStartElement("Manager");
				writer.writeAttribute("className", this.managerClass.getName());
				writer.writeAttribute("granularity", this.parameters.getSessionPersistenceGranularity().toString());
				writer.writeAttribute("marshaller", this.parameters.getSessionMarshallerFactory().toString());
				writer.writeAttribute("configuration", """
{
	"local-cache" : {
		"expiration" : {
			"interval" : 1000
		},
		"locking" : {
			"isolation" : "REPEATABLE_READ"
		},
		"transaction" : {
			"mode" : "BATCH",
			"locking" : "PESSIMISTIC"
		}
	}
}""");
				// TODO Figure out how to configure HASH_DISTRIBUTION_AWARE w/bridge networking
				writer.writeAttribute("uri", String.format("hotrod://%s:%s@%s:%s?client_intelligence=%s", container.getUsername(), String.valueOf(container.getPassword()), container.getHost(), container.getPort(), container.isPortMapping() ? ClientIntelligence.BASIC : ClientIntelligence.HASH_DISTRIBUTION_AWARE));
				writer.writeEndElement();
			}
			writer.writeEndElement();
			writer.writeEndDocument();
			writer.close();
		} catch (XMLStreamException e) {
			throw new IllegalStateException(e);
		}
		return super.createArchive(configuration).addAsManifestResource(new StringAsset(stringWriter.toString()), "context.xml");
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
