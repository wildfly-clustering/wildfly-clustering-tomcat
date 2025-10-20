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
 * Provides the session identifier for Tomcat via upper-case hex decoding.
 * @author Paul Ferraro
 */
@MetaInfServices(IdentifierMarshallerProvider.class)
public class TomcatIdentifierMarshallerProvider implements IdentifierMarshallerProvider {
	/**
	 * Creates a provider of an identifier marshaller.
	 */
	public TomcatIdentifierMarshallerProvider() {
	}

	@Override
	public Marshaller<String, ByteBuffer> getMarshaller() {
		return IdentifierMarshaller.HEX_UPPER;
	}
}
