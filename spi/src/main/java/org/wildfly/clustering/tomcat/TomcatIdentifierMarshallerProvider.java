/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.nio.ByteBuffer;

import org.kohsuke.MetaInfServices;
import org.wildfly.clustering.marshalling.Marshaller;
import org.wildfly.clustering.session.IdentifierMarshaller;
import org.wildfly.clustering.session.IdentifierMarshallerProvider;

/**
 * Informs WildFly clustering how best to serialize Tomcat's session identifier.
 * @author Paul Ferraro
 */
@MetaInfServices(IdentifierMarshallerProvider.class)
public class TomcatIdentifierMarshallerProvider implements IdentifierMarshallerProvider {

	@Override
	public Marshaller<String, ByteBuffer> getMarshaller() {
		return IdentifierMarshaller.HEX;
	}
}
