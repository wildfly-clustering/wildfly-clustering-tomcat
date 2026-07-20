/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.io.ObjectInputFilter;
import java.util.Optional;

import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.UnmarshallingObjectInputFilter;
import org.wildfly.clustering.function.BiFunction;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.marshalling.ByteBufferMarshaller;
import org.wildfly.clustering.marshalling.java.JavaByteBufferMarshaller;
import org.wildfly.clustering.marshalling.jboss.JBossByteBufferMarshaller;
import org.wildfly.clustering.marshalling.jboss.MarshallingConfigurationBuilder;
import org.wildfly.clustering.marshalling.protostream.ClassLoaderResolver;
import org.wildfly.clustering.marshalling.protostream.ImmutableSerializationContext;
import org.wildfly.clustering.marshalling.protostream.ProtoStreamByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.ProtoStreamConfiguration;

/**
 * Enumeration of supported session attribute marshaller factories.
 * @author Paul Ferraro
 */
public enum SessionMarshallerFactory implements BiFunction<UnaryOperator<String>, ClassLoader, ByteBufferMarshaller> {
	/** Creates a marshaller using JDK  serialization */
	JAVA() {
		@Override
		public ByteBufferMarshaller apply(UnaryOperator<String> properties, ClassLoader loader) {
			return new JavaByteBufferMarshaller(loader, this.inputFilter(properties));
		}
	},
	/** Creates a marshaller using JBoss Marshalling */
	JBOSS() {
		@Override
		public ByteBufferMarshaller apply(UnaryOperator<String> properties, ClassLoader loader) {
			MarshallingConfiguration configuration = MarshallingConfigurationBuilder.newInstance(new SimpleClassResolver(loader)).load(loader).build();
			this.serialFilter(properties).map(UnmarshallingObjectInputFilter.Factory::createFilter).ifPresent(configuration::setUnmarshallingFilter);
			return new JBossByteBufferMarshaller(configuration, loader);
		}
	},
	/** Creates a marshaller using ProtoStream */
	PROTOSTREAM() {
		@Override
		public ByteBufferMarshaller apply(UnaryOperator<String> properties, ClassLoader loader) {
			return new ProtoStreamByteBufferMarshaller(ImmutableSerializationContext.Builder.with(ProtoStreamConfiguration.Builder.with(ClassLoaderResolver.of(loader)).withObjectInputFilter(this.inputFilter(properties)).build()).build());
		}
	},
	;

	ObjectInputFilter inputFilter(UnaryOperator<String> properties) {
		return this.serialFilter(properties).map(ObjectInputFilter.Config::createFilter).orElse(ObjectInputFilter.Config.getSerialFilter());
	}

	Optional<String> serialFilter(UnaryOperator<String> properties) {
		return Optional.ofNullable(properties.apply("jdk.serialFilter"));
	}
}
