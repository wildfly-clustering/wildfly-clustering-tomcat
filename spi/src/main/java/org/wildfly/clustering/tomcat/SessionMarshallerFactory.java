/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.io.ObjectInputFilter;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import org.jboss.marshalling.SimpleClassResolver;
import org.wildfly.clustering.marshalling.ByteBufferMarshaller;
import org.wildfly.clustering.marshalling.java.JavaByteBufferMarshaller;
import org.wildfly.clustering.marshalling.jboss.JBossByteBufferMarshaller;
import org.wildfly.clustering.marshalling.jboss.MarshallingConfigurationBuilder;
import org.wildfly.clustering.marshalling.protostream.ClassLoaderMarshaller;
import org.wildfly.clustering.marshalling.protostream.ProtoStreamByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.SerializationContextBuilder;

/**
 * Enumeration of supported session attribute marshaller factories.
 * @author Paul Ferraro
 */
public enum SessionMarshallerFactory implements BiFunction<UnaryOperator<String>, ClassLoader, ByteBufferMarshaller> {
	/** Creates a marshaller using JDK  serialization */
	JAVA() {
		@Override
		public ByteBufferMarshaller apply(UnaryOperator<String> properties, ClassLoader loader) {
			ObjectInputFilter filter = Optional.ofNullable(properties.apply("jdk.serialFilter")).map(ObjectInputFilter.Config::createFilter).orElse(null);
			return new JavaByteBufferMarshaller(loader, filter);
		}
	},
	/** Creates a marshaller using JBoss Marshalling */
	JBOSS() {
		@Override
		public ByteBufferMarshaller apply(UnaryOperator<String> properties, ClassLoader loader) {
			return new JBossByteBufferMarshaller(MarshallingConfigurationBuilder.newInstance(new SimpleClassResolver(loader)).load(loader).build(), loader);
		}
	},
	/** Creates a marshaller using ProtoStream */
	PROTOSTREAM() {
		@Override
		public ByteBufferMarshaller apply(UnaryOperator<String> properties, ClassLoader loader) {
			return new ProtoStreamByteBufferMarshaller(SerializationContextBuilder.newInstance(ClassLoaderMarshaller.of(loader)).load(loader).build());
		}
	},
	;
}
