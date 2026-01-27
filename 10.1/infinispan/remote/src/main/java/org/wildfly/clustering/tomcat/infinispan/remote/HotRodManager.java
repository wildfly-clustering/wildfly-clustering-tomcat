/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.infinispan.remote;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import jakarta.servlet.ServletContext;

import org.infinispan.client.hotrod.DataFormat;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheContainer;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.NearCacheMode;
import org.infinispan.client.hotrod.configuration.RemoteCacheConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.TransactionMode;
import org.infinispan.client.hotrod.impl.HotRodURI;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.marshall.Marshaller;
import org.wildfly.clustering.cache.infinispan.marshalling.MediaTypes;
import org.wildfly.clustering.cache.infinispan.marshalling.UserMarshaller;
import org.wildfly.clustering.cache.infinispan.remote.RemoteCacheConfiguration;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.marshalling.protostream.ClassLoaderMarshaller;
import org.wildfly.clustering.marshalling.protostream.ProtoStreamByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.SerializationContextBuilder;
import org.wildfly.clustering.session.SessionManagerFactory;
import org.wildfly.clustering.session.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.session.infinispan.remote.HotRodSessionManagerFactory;
import org.wildfly.clustering.tomcat.catalina.AbstractManager;
import org.wildfly.clustering.tomcat.catalina.CatalinaSessionContext;

/**
 * Distributed {@link org.apache.catalina.Manager} that persists sessions to a remote Infinispan cluster.
 * @author Paul Ferraro
 */
public class HotRodManager extends AbstractManager {

	private final Properties properties = new Properties();

	private volatile String templateName = null;
	private String configuration = """
{
	"distributed-cache" : {
		"mode" : "SYNC",
		"statistics" : "true",
		"encoding" : {
			"key" : {
				"media-type" : "application/octet-stream"
			},
			"value" : {
				"media-type" : "application/octet-stream"
			}
		},
		"transaction" : {
			"mode" : "BATCH",
			"locking" : "PESSIMISTIC"
		}
	}
}""";
	private volatile URI uri = null;

	/**
	 * Creates a new distributed manager.
	 */
	public HotRodManager() {
	}

	/**
	 * Specifies the HotRod URI of this manager.
	 * @param uri a HotRod URI.
	 */
	public void setUri(String uri) {
		this.uri = URI.create(uri);
	}

	/**
	 * Specifies a HotRod property.
	 * @param name a property name
	 * @param value a property value
	 */
	public void setProperty(String name, String value) {
		this.properties.setProperty("infinispan.client.hotrod." + name, value);
	}

	/**
	 * Specifies the name of a server-side cache configuration.
	 * @param templateName the name of a server-side cache configuration
	 */
	public void setTemplate(String templateName) {
		this.templateName = templateName;
	}

	/**
	 * Specifies a server-side cache configuration.
	 * @param configuration a server-side cache configuration
	 */
	public void setConfiguration(String configuration) {
		this.configuration = configuration;
	}

	@Override
	protected Map.Entry<SessionManagerFactory<ServletContext, CatalinaSessionContext>, UnaryOperator<String>> createSessionManagerFactory(SessionManagerFactoryConfiguration<CatalinaSessionContext> config, String localRoute, Consumer<Runnable> stopTasks) {
		ClassLoader containerLoader = HotRodSessionManagerFactory.class.getClassLoader();
		Marshaller marshaller = new UserMarshaller(MediaTypes.WILDFLY_PROTOSTREAM, new ProtoStreamByteBufferMarshaller(SerializationContextBuilder.newInstance(ClassLoaderMarshaller.of(containerLoader)).load(containerLoader).build()));
		Configuration configuration = Optional.ofNullable(this.uri).map(HotRodURI::create).map(HotRodURI::toConfigurationBuilder).orElseGet(ConfigurationBuilder::new)
				.withProperties(this.properties)
				.marshaller(marshaller)
				.build();

		Consumer<RemoteCacheConfigurationBuilder> configurator = builder -> builder.forceReturnValues(false).nearCacheMode(config.getSizeThreshold().isPresent() ? NearCacheMode.INVALIDATED : NearCacheMode.DISABLED).transactionMode(TransactionMode.NONE);
		configuration.addRemoteCache(config.getDeploymentName(), configurator.andThen((this.templateName != null) ? builder -> builder.templateName(this.templateName) : builder -> builder.configuration(this.configuration)));

		@SuppressWarnings("resource")
		RemoteCacheContainer container = new RemoteCacheManager(configuration);
		container.start();
		stopTasks.accept(container::stop);

		RemoteCache<?, ?> cache = container.getCache(config.getDeploymentName());
		cache.start();
		stopTasks.accept(cache::stop);

		return Map.entry(new HotRodSessionManagerFactory<>(new HotRodSessionManagerFactory.Configuration<>() {
			@Override
			public SessionManagerFactoryConfiguration<CatalinaSessionContext> getSessionManagerFactoryConfiguration() {
				return config;
			}

			@Override
			public RemoteCacheConfiguration getCacheConfiguration() {
				return RemoteCacheConfiguration.of(cache.withDataFormat(DataFormat.builder().keyType(MediaType.APPLICATION_OBJECT).keyMarshaller(marshaller).valueType(MediaType.APPLICATION_OBJECT).valueMarshaller(marshaller).build()));
			}
		}), UnaryOperator.of(localRoute));
	}
}
