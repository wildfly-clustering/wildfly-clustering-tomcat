/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.infinispan.embedded;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import javax.management.ObjectName;
import javax.servlet.ServletContext;

import io.reactivex.rxjava3.schedulers.Schedulers;

import org.apache.catalina.LifecycleException;
import org.infinispan.Cache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.global.GlobalJmxConfiguration;
import org.infinispan.configuration.global.ShutdownHookBehavior;
import org.infinispan.configuration.global.TransportConfiguration;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.expiration.ExpirationManager;
import org.infinispan.globalstate.ConfigurationStorage;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.impl.ListenerInvocation;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;
import org.infinispan.remoting.transport.jgroups.JGroupsChannelConfigurator;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.transaction.tm.EmbeddedTransactionManager;
import org.infinispan.util.concurrent.BlockingManager;
import org.infinispan.util.concurrent.NonBlockingManager;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.jmx.JmxConfigurator;
import org.wildfly.clustering.cache.Key;
import org.wildfly.clustering.cache.infinispan.embedded.container.DataContainerConfigurationBuilder;
import org.wildfly.clustering.cache.infinispan.marshalling.MediaTypes;
import org.wildfly.clustering.cache.infinispan.marshalling.UserMarshaller;
import org.wildfly.clustering.marshalling.ByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.ClassLoaderMarshaller;
import org.wildfly.clustering.marshalling.protostream.ProtoStreamByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.SerializationContextBuilder;
import org.wildfly.clustering.server.group.GroupCommandDispatcherFactory;
import org.wildfly.clustering.server.infinispan.CacheContainerGroupMember;
import org.wildfly.clustering.server.infinispan.affinity.UnaryGroupMemberAffinity;
import org.wildfly.clustering.server.infinispan.dispatcher.CacheContainerCommandDispatcherFactory;
import org.wildfly.clustering.server.infinispan.dispatcher.ChannelEmbeddedCacheManagerCommandDispatcherFactoryConfiguration;
import org.wildfly.clustering.server.infinispan.dispatcher.EmbeddedCacheManagerCommandDispatcherFactory;
import org.wildfly.clustering.server.infinispan.dispatcher.LocalEmbeddedCacheManagerCommandDispatcherFactoryConfiguration;
import org.wildfly.clustering.server.jgroups.ChannelGroupMember;
import org.wildfly.clustering.server.jgroups.dispatcher.ChannelCommandDispatcherFactory;
import org.wildfly.clustering.server.jgroups.dispatcher.JChannelCommandDispatcherFactory;
import org.wildfly.clustering.server.jgroups.dispatcher.JChannelCommandDispatcherFactoryConfiguration;
import org.wildfly.clustering.session.SessionManagerFactory;
import org.wildfly.clustering.session.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.session.cache.affinity.UnarySessionAffinity;
import org.wildfly.clustering.session.infinispan.embedded.InfinispanSessionManagerFactory;
import org.wildfly.clustering.session.infinispan.embedded.InfinispanSessionManagerFactoryConfiguration;
import org.wildfly.clustering.session.infinispan.embedded.metadata.SessionMetaDataKey;
import org.wildfly.clustering.session.spec.servlet.HttpSessionActivationListenerProvider;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;
import org.wildfly.clustering.tomcat.catalina.AbstractManager;
import org.wildfly.clustering.tomcat.catalina.CatalinaSessionContext;

/**
 * Distributed Manager that stores sessions within an embedded Infinispan cache.
 * @author Paul Ferraro
 */
public class InfinispanManager extends AbstractManager {
	static final System.Logger LOGGER = System.getLogger(InfinispanManager.class.getPackageName());
	private static final AtomicInteger COUNTER = new AtomicInteger(0);

	private volatile String resourceName = "infinispan.xml";
	private volatile String cacheName = null;

	public void setResource(String resourceName) {
		this.resourceName = resourceName;
	}

	public void setTemplate(String cacheName) {
		this.cacheName = cacheName;
	}

	@Override
	protected Map.Entry<SessionManagerFactory<ServletContext, CatalinaSessionContext>, UnaryOperator<String>> createSessionManagerFactory(SessionManagerFactoryConfiguration<CatalinaSessionContext> config, String localRoute, Consumer<Runnable> stopTasks) throws LifecycleException {
		COUNTER.incrementAndGet();
		stopTasks.accept(() -> {
			// Stop RxJava schedulers when no longer in use
			if (COUNTER.decrementAndGet() == 0) {
				Schedulers.shutdown();
			}
		});

		ClassLoader loader = this.getContext().getLoader().getClassLoader();
		try {
			// Locate as classpath resource
			URL url = loader.getResource(this.resourceName);
			if (url == null) {
				// Attempt to locate on filesystem
				File file = new File(this.resourceName);
				if (file.exists()) {
					url = file.toURI().toURL();
				} else {
					throw new IllegalArgumentException(this.resourceName);
				}
			}
			LOGGER.log(System.Logger.Level.DEBUG, "Configuring Infinispan from {0}", url);

			ConfigurationBuilderHolder holder = new ParserRegistry(loader, false, System.getProperties()).parse(url);
			GlobalConfigurationBuilder global = holder.getGlobalConfigurationBuilder();
			String containerName = global.cacheContainer().name();
			TransportConfiguration transport = global.transport().nodeName(localRoute).create();

			JGroupsChannelConfigurator configurator = (transport.transport() != null) ? new JChannelConfigurator(transport, loader) : null;
			JChannel channel = (configurator != null) ? configurator.createChannel(null) : null;
			if (channel != null) {
				channel.setName(transport.nodeName());
				channel.setDiscardOwnMessages(true);
				LOGGER.log(System.Logger.Level.DEBUG, "Connecting {0} to {1}", transport.nodeName(), transport.clusterName());
				channel.connect(transport.clusterName());
				LOGGER.log(System.Logger.Level.DEBUG, "Connected {0} to {1} with view: {2}", channel.getName(), channel.getClusterName(), channel.view().getMembers());
				stopTasks.accept(() -> {
					LOGGER.log(System.Logger.Level.DEBUG, "Disconnecting {0} from {1} with view: {2}", channel.getName(), channel.getClusterName(), channel.view().getMembers());
					try {
						channel.disconnect();
						LOGGER.log(System.Logger.Level.DEBUG, "Disconnected {0} from {1}", transport.nodeName(), transport.clusterName());
					} finally {
						channel.close();
					}
				});

				GlobalJmxConfiguration jmx = global.jmx().create();
				if (jmx.enabled()) {
					ObjectName prefix = new ObjectName(jmx.domain(), "manager", ObjectName.quote(containerName));
					JmxConfigurator.registerChannel(channel, ManagementFactory.getPlatformMBeanServer(), prefix, transport.clusterName(), true);
					stopTasks.accept(() -> {
						try {
							JmxConfigurator.unregisterChannel(channel, ManagementFactory.getPlatformMBeanServer(), prefix, transport.clusterName());
						} catch (Exception e) {
							LOGGER.log(System.Logger.Level.WARNING, e.getLocalizedMessage(), e);
						}
					});
				}

				Properties properties = new Properties();
				properties.put(JGroupsTransport.CHANNEL_CONFIGURATOR, new ForkChannelConfigurator(channel, containerName));
				global.transport().withProperties(properties);
			}

			ChannelCommandDispatcherFactory channelCommandDispatcherFactory = (channel != null) ? new JChannelCommandDispatcherFactory(new JChannelCommandDispatcherFactoryConfiguration() {
				@Override
				public JChannel getChannel() {
					return channel;
				}

				@Override
				public ByteBufferMarshaller getMarshaller() {
					return this.getMarshallerFactory().apply(JChannelCommandDispatcherFactory.class.getClassLoader());
				}

				@Override
				public Function<ClassLoader, ByteBufferMarshaller> getMarshallerFactory() {
					return loader -> new ProtoStreamByteBufferMarshaller(SerializationContextBuilder.newInstance(ClassLoaderMarshaller.of(loader)).load(loader).build());
				}

				@Override
				public Predicate<Message> getUnknownForkPredicate() {
					return Predicate.not(Message::hasPayload);
				}
			}) : null;
			if (channelCommandDispatcherFactory != null) {
				stopTasks.accept(channelCommandDispatcherFactory::close);
			}

			global.classLoader(loader)
					.shutdown().hookBehavior(ShutdownHookBehavior.DONT_REGISTER)
					.blockingThreadPool().threadFactory(new DefaultBlockingThreadFactory(BlockingManager.class))
					.expirationThreadPool().threadFactory(new DefaultBlockingThreadFactory(ExpirationManager.class))
					.listenerThreadPool().threadFactory(new DefaultBlockingThreadFactory(ListenerInvocation.class))
					.nonBlockingThreadPool().threadFactory(new DefaultNonBlockingThreadFactory(NonBlockingManager.class))
					.serialization()
						.marshaller(new UserMarshaller(MediaTypes.WILDFLY_PROTOSTREAM, new ProtoStreamByteBufferMarshaller(SerializationContextBuilder.newInstance(ClassLoaderMarshaller.of(loader)).load(loader).build())))
						// Register dummy serialization context initializer, to bypass service loading in org.infinispan.marshall.protostream.impl.SerializationContextRegistryImpl
						// Otherwise marshaller auto-detection will not work
						.addContextInitializer(new SerializationContextInitializer() {
							@Deprecated
							@Override
							public String getProtoFile() {
								return null;
							}

							@Deprecated
							@Override
							public String getProtoFileName() {
								return null;
							}

							@Override
							public void registerMarshallers(SerializationContext context) {
							}

							@Override
							public void registerSchema(SerializationContext context) {
							}
						})
					.globalState().configurationStorage(ConfigurationStorage.IMMUTABLE).disable();

			EmbeddedCacheManager container = new DefaultCacheManager(holder, false);
			container.start();
			stopTasks.accept(container::stop);

			Configuration template = (this.cacheName != null) ? container.getCacheConfiguration(this.cacheName) : container.getDefaultCacheConfiguration();
			if (template == null) {
				throw new IllegalArgumentException(this.cacheName);
			}
			ConfigurationBuilder builder = new ConfigurationBuilder().read(template).template(false);
			builder.encoding().mediaType(MediaType.APPLICATION_OBJECT_TYPE);

			if (template.invocationBatching().enabled()) {
				builder.invocationBatching().disable();
				builder.transaction().transactionMode(TransactionMode.TRANSACTIONAL).transactionManagerLookup(EmbeddedTransactionManager::getInstance);
			}

			// Disable expiration
			builder.expiration().lifespan(-1).maxIdle(-1).disableReaper().wakeUpInterval(-1);

			OptionalInt maxActiveSessions = config.getMaxActiveSessions();
			EvictionStrategy eviction = maxActiveSessions.isPresent() ? EvictionStrategy.REMOVE : EvictionStrategy.MANUAL;
			builder.memory().storage(StorageType.HEAP)
					.whenFull(eviction)
					.maxCount(maxActiveSessions.orElse(-1))
					;
			if (eviction.isEnabled()) {
				// Only evict meta-data entries
				// We will cascade eviction to the remaining entries for a given session
				builder.addModule(DataContainerConfigurationBuilder.class).evictable(SessionMetaDataKey.class::isInstance);
			}

			String cacheName = config.getDeploymentName();
			container.defineConfiguration(cacheName, builder.build());
			stopTasks.accept(() -> container.undefineConfiguration(cacheName));

			CacheContainerCommandDispatcherFactory commandDispatcherFactory = (channelCommandDispatcherFactory != null) ? new EmbeddedCacheManagerCommandDispatcherFactory<>(new ChannelEmbeddedCacheManagerCommandDispatcherFactoryConfiguration() {
				@Override
				public GroupCommandDispatcherFactory<org.jgroups.Address, ChannelGroupMember> getCommandDispatcherFactory() {
					return channelCommandDispatcherFactory;
				}

				@Override
				public EmbeddedCacheManager getCacheContainer() {
					return container;
				}
			}) : new EmbeddedCacheManagerCommandDispatcherFactory<>(new LocalEmbeddedCacheManagerCommandDispatcherFactoryConfiguration() {
				@Override
				public EmbeddedCacheManager getCacheContainer() {
					return container;
				}
			});

			Cache<Key<String>, ?> cache = container.getCache(cacheName);
			cache.start();
			stopTasks.accept(cache::stop);

			return Map.entry(new InfinispanSessionManagerFactory<>(config, HttpSessionProvider.INSTANCE, HttpSessionActivationListenerProvider.INSTANCE, new InfinispanSessionManagerFactoryConfiguration() {
				@SuppressWarnings("unchecked")
				@Override
				public <K, V> Cache<K, V> getCache() {
					return (Cache<K, V>) cache;
				}

				@Override
				public CacheContainerCommandDispatcherFactory getCommandDispatcherFactory() {
					return commandDispatcherFactory;
				}
			}), new UnarySessionAffinity<>(new UnaryGroupMemberAffinity<>(cache, commandDispatcherFactory.getGroup()), CacheContainerGroupMember::getName));
		} catch (LifecycleException e) {
			throw e;
		} catch (Exception e) {
			throw new LifecycleException(e);
		}
	}
}
