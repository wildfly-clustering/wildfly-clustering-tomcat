/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.wildfly.clustering.session.container.AbstractSessionManagerITCase;
import org.wildfly.clustering.session.container.SessionManagementTesterConfiguration;

/**
 * @author Paul Ferraro
 */
public abstract class AbstractInfinispanSessionManagerITCase extends AbstractSessionManagerITCase<SessionManagementArguments, WebArchive> {

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

	@ParameterizedTest
	@ArgumentsSource(SessionManagementArgumentsProvider.class)
	@RunAsClient
	@Override
	public void accept(SessionManagementArguments parameters) {
		super.accept(parameters);
	}

	@Override
	public WebArchive createArchive(SessionManagementArguments arguments) {
		XMLOutputFactory factory = XMLOutputFactory.newFactory();
		StringWriter stringWriter = new StringWriter();
		try {
			XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);
			writer.writeStartDocument(StandardCharsets.UTF_8.displayName(), "1.0");
			writer.writeStartElement("Context");

			writer.writeStartElement("Manager");
			writer.writeAttribute("className", this.managerClass.getName());
			writer.writeAttribute("granularity", arguments.getSessionPersistenceGranularity().toString());
			writer.writeAttribute("marshaller", arguments.getSessionMarshallerFactory().toString());
			writer.writeEndElement();

			writer.writeEndElement();
			writer.writeEndDocument();
			writer.close();
		} catch (XMLStreamException e) {
			throw new IllegalStateException(e);
		}
		return super.createArchive(arguments)
				.addAsWebInfResource("infinispan.xml", "classes/infinispan.xml")
				.addAsManifestResource(new StringAsset(stringWriter.toString()), "context.xml");
	}
}
