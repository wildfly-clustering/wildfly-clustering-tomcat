/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat.infinispan.remote;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import jakarta.servlet.ServletContext;

import org.infinispan.client.hotrod.DefaultTemplate;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheContainer;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.NearCacheMode;
import org.infinispan.client.hotrod.configuration.TransactionMode;
import org.infinispan.client.hotrod.impl.HotRodURI;
import org.wildfly.clustering.cache.function.Functions;
import org.wildfly.clustering.cache.infinispan.marshalling.MediaTypes;
import org.wildfly.clustering.cache.infinispan.marshalling.UserMarshaller;
import org.wildfly.clustering.cache.infinispan.remote.RemoteCacheConfiguration;
import org.wildfly.clustering.marshalling.protostream.ClassLoaderMarshaller;
import org.wildfly.clustering.marshalling.protostream.ProtoStreamByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.SerializationContextBuilder;
import org.wildfly.clustering.session.SessionManagerFactory;
import org.wildfly.clustering.session.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.session.infinispan.remote.HotRodSessionManagerFactory;
import org.wildfly.clustering.session.spec.servlet.HttpSessionActivationListenerProvider;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;
import org.wildfly.clustering.tomcat.catalina.AbstractManager;
import org.wildfly.clustering.tomcat.catalina.CatalinaSessionContext;

/**
 * Distributed {@link org.apache.catalina.Manager} that persists sessions to a remote Infinispan cluster.
 * @author Paul Ferraro
 */
public class HotRodManager extends AbstractManager {

	private final Properties properties = new Properties();

	private volatile String templateName = DefaultTemplate.DIST_SYNC.getTemplateName();
	private volatile URI uri = null;

	public void setUri(String uri) {
		this.uri = URI.create(uri);
	}

	public void setProperty(String name, String value) {
		this.properties.setProperty("infinispan.client.hotrod." + name, value);
	}

	public void setTemplate(String templateName) {
		this.templateName = templateName;
	}

	@Override
	protected Map.Entry<SessionManagerFactory<ServletContext, CatalinaSessionContext>, UnaryOperator<String>> createSessionManagerFactory(SessionManagerFactoryConfiguration<CatalinaSessionContext> config, String localRoute, Consumer<Runnable> stopTasks) {
		ClassLoader containerLoader = HotRodSessionManagerFactory.class.getClassLoader();
		Configuration configuration = Optional.ofNullable(this.uri).map(HotRodURI::create).map(HotRodURI::toConfigurationBuilder).orElseGet(ConfigurationBuilder::new)
				.withProperties(this.properties)
				.marshaller(new UserMarshaller(MediaTypes.WILDFLY_PROTOSTREAM, new ProtoStreamByteBufferMarshaller(SerializationContextBuilder.newInstance(ClassLoaderMarshaller.of(containerLoader)).load(containerLoader).build())))
				.build();

		configuration.addRemoteCache(config.getDeploymentName(), builder -> builder.forceReturnValues(false).nearCacheMode(config.getMaxActiveSessions().isPresent() ? NearCacheMode.INVALIDATED : NearCacheMode.DISABLED).transactionMode(TransactionMode.NONE).templateName(this.templateName));

		@SuppressWarnings("resource")
		RemoteCacheContainer container = new RemoteCacheManager(configuration);
		container.start();
		stopTasks.accept(container::stop);

		RemoteCache<?, ?> cache = container.getCache(config.getDeploymentName());
		cache.start();
		stopTasks.accept(cache::stop);

		RemoteCacheConfiguration hotrod = new RemoteCacheConfiguration() {
			@SuppressWarnings("unchecked")
			@Override
			public <K, V> RemoteCache<K, V> getCache() {
				return (RemoteCache<K, V>) cache;
			}
		};

		return Map.entry(new HotRodSessionManagerFactory<>(config, HttpSessionProvider.INSTANCE, HttpSessionActivationListenerProvider.INSTANCE, hotrod), Functions.constantOperator(localRoute));
	}
}
