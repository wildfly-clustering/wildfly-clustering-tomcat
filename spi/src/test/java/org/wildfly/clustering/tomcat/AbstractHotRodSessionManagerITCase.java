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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.wildfly.clustering.container.infinispan.InfinispanServerExtension;

/**
 * @author Paul Ferraro
 */
public abstract class AbstractHotRodSessionManagerITCase extends AbstractSessionManagerITCase {

	@RegisterExtension
	private final InfinispanServerExtension extension = new InfinispanServerExtension();

	private final Class<?> managerClass;

	protected AbstractHotRodSessionManagerITCase(Class<?> managerClass, Class<?> endpointClass) {
		super(endpointClass);
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
			writer.writeAttribute("configuration", """
{ "local-cache" : { "encoding" : { "key" : { "media-type" : "application/octet-stream" }, "value" : { "media-type" : "application/octet-stream" }}, "expiration" : { "interval" : 1000 }, "locking" : { "isolation" : "REPEATABLE_READ" }, "transaction" : { "mode" : "NON_XA", "locking" : "PESSIMISTIC" }}}""");
			writer.writeAttribute("uri", this.extension.getURI().toString(true));
			writer.writeEndElement();

			writer.writeEndElement();
			writer.writeEndDocument();
			writer.close();
		} catch (XMLStreamException e) {
			throw new IllegalStateException(e);
		}
		return super.createArchive(arguments)
				.addAsManifestResource(new StringAsset(stringWriter.toString()), "context.xml");
	}
}
