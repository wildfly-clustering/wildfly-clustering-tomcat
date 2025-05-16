/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

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
				writer.writeEndElement();
			}
			writer.writeEndElement();
			writer.writeEndDocument();
			writer.close();
		} catch (XMLStreamException e) {
			throw new IllegalStateException(e);
		}
		return super.createArchive(configuration)
				.addAsResource("infinispan.xml")
				.addAsManifestResource(new StringAsset(stringWriter.toString()), "context.xml");
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
