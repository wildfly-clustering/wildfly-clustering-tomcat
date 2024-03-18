/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.tomcat.infinispan.remote;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.servlet.ServletContext;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.infinispan.client.hotrod.DefaultTemplate;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheContainer;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.NearCacheMode;
import org.infinispan.client.hotrod.configuration.TransactionMode;
import org.infinispan.client.hotrod.impl.HotRodURI;
import org.wildfly.clustering.cache.function.IntPredicates;
import org.wildfly.clustering.cache.infinispan.batch.TransactionBatch;
import org.wildfly.clustering.cache.infinispan.marshalling.protostream.ProtoStreamMarshaller;
import org.wildfly.clustering.cache.infinispan.remote.RemoteCacheConfiguration;
import org.wildfly.clustering.marshalling.ByteBufferMarshaller;
import org.wildfly.clustering.marshalling.protostream.ClassLoaderMarshaller;
import org.wildfly.clustering.server.immutable.Immutability;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.SessionAttributePersistenceStrategy;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.SessionManagerConfiguration;
import org.wildfly.clustering.session.SessionManagerFactory;
import org.wildfly.clustering.session.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.session.infinispan.remote.HotRodSessionManagerFactory;
import org.wildfly.clustering.session.spec.servlet.HttpSessionActivationListenerProvider;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;
import org.wildfly.clustering.tomcat.SessionMarshallerFactory;
import org.wildfly.clustering.tomcat.SessionPersistenceGranularity;
import org.wildfly.clustering.tomcat.catalina.CatalinaIdentifierFactory;
import org.wildfly.clustering.tomcat.catalina.CatalinaManager;
import org.wildfly.clustering.tomcat.catalina.CatalinaSessionContext;
import org.wildfly.clustering.tomcat.catalina.CatalinaSessionExpirationListener;
import org.wildfly.clustering.tomcat.catalina.DistributableManager;

/**
 * Distributed Manager implementation that configures a HotRod client.
 * @author Paul Ferraro
 */
public class HotRodManager extends ManagerBase {

	static final ToIntFunction<ServletContext> SESSION_TIMEOUT_FUNCTION = ServletContext::getSessionTimeout;

	private final Properties properties = new Properties();
	private final List<Runnable> stopTasks = new LinkedList<>();

	private volatile CatalinaManager<TransactionBatch> manager;
	private volatile SessionAttributePersistenceStrategy persistenceStrategy = SessionPersistenceGranularity.SESSION.get();
	private volatile SessionMarshallerFactory marshallerFactory = SessionMarshallerFactory.JBOSS;
	private volatile String templateName = DefaultTemplate.DIST_SYNC.getTemplateName();
	private volatile URI uri = null;

	public void setUri(String uri) {
		this.uri = URI.create(uri);
	}

	public void setProperty(String name, String value) {
		this.properties.setProperty("infinispan.client.hotrod." + name, value);
	}

	public void setPersistenceStrategy(SessionAttributePersistenceStrategy strategy) {
		this.persistenceStrategy = strategy;
	}

	public void setGranularity(SessionPersistenceGranularity granularity) {
		this.setPersistenceStrategy(granularity.get());
	}

	public void setGranularity(String granularity) {
		this.setGranularity(SessionPersistenceGranularity.valueOf(granularity));
	}

	public void setTemplate(String templateName) {
		this.templateName = templateName;
	}

	public void setMarshallerFactory(SessionMarshallerFactory marshallerFactory) {
		this.marshallerFactory = marshallerFactory;
	}

	public void setMarshaller(String marshallerFactory) {
		this.setMarshallerFactory(SessionMarshallerFactory.valueOf(marshallerFactory));
	}

	@Override
	protected void startInternal() throws LifecycleException {
		super.startInternal();

		Context context = this.getContext();
		Host host = (Host) context.getParent();
		Engine engine = (Engine) host.getParent();
		ServletContext servletContext = context.getServletContext();
		// Deployment name = host name + context path + version
		String deploymentName = host.getName() + context.getName();
		OptionalInt maxActiveSessions = IntStream.of(this.getMaxActiveSessions()).filter(IntPredicates.POSITIVE).findFirst();
		SessionAttributePersistenceStrategy strategy = this.persistenceStrategy;

		ClassLoader containerLoader = HotRodSessionManagerFactory.class.getClassLoader();
		Configuration configuration = Optional.ofNullable(this.uri).map(HotRodURI::create).map(HotRodURI::toConfigurationBuilder).orElseGet(ConfigurationBuilder::new)
				.withProperties(this.properties)
				.marshaller(new ProtoStreamMarshaller(ClassLoaderMarshaller.of(containerLoader), builder -> builder.load(containerLoader)))
				.build();

		configuration.addRemoteCache(deploymentName, builder -> builder.forceReturnValues(false).nearCacheMode(maxActiveSessions.isPresent() ? NearCacheMode.INVALIDATED : NearCacheMode.DISABLED).transactionMode(TransactionMode.NONE).templateName(this.templateName));

		@SuppressWarnings("resource")
		RemoteCacheContainer container = new RemoteCacheManager(configuration);
		container.start();
		this.stopTasks.add(container::stop);

		ClassLoader loader = context.getLoader().getClassLoader();
		ByteBufferMarshaller marshaller = this.marshallerFactory.apply(servletContext::getInitParameter, loader);

		List<Immutability> loadedImmutabilities = new LinkedList<>();
		for (Immutability loadedImmutability : ServiceLoader.load(Immutability.class, loader)) {
			loadedImmutabilities.add(loadedImmutability);
		}
		Immutability immutability = Immutability.composite(Stream.concat(Stream.of(Immutability.getDefault()), loadedImmutabilities.stream()).collect(Collectors.toList()));

		SessionManagerFactoryConfiguration<CatalinaSessionContext> sessionManagerFactoryConfig = new SessionManagerFactoryConfiguration<>() {
			@Override
			public OptionalInt getMaxActiveSessions() {
				return maxActiveSessions;
			}

			@Override
			public SessionAttributePersistenceStrategy getAttributePersistenceStrategy() {
				return strategy;
			}

			@Override
			public String getDeploymentName() {
				return deploymentName;
			}

			@Override
			public ByteBufferMarshaller getMarshaller() {
				return marshaller;
			}

			@Override
			public String getServerName() {
				return engine.getService().getName();
			}

			@Override
			public Supplier<CatalinaSessionContext> getSessionContextFactory() {
				return CatalinaSessionContext::new;
			}

			@Override
			public Immutability getImmutability() {
				return immutability;
			}
		};

		RemoteCache<?, ?> cache = container.getCache(deploymentName);
		cache.start();
		this.stopTasks.add(cache::stop);

		RemoteCacheConfiguration hotrod = new RemoteCacheConfiguration() {
			@SuppressWarnings("unchecked")
			@Override
			public <K, V> RemoteCache<K, V> getCache() {
				return (RemoteCache<K, V>) cache;
			}
		};

		SessionManagerFactory<ServletContext, CatalinaSessionContext, TransactionBatch> managerFactory = new HotRodSessionManagerFactory<>(sessionManagerFactoryConfig, HttpSessionProvider.INSTANCE, HttpSessionActivationListenerProvider.INSTANCE, hotrod);
		this.stopTasks.add(managerFactory::close);

		Consumer<ImmutableSession> expirationListener = new CatalinaSessionExpirationListener(context);
		Supplier<String> identifierFactory = new CatalinaIdentifierFactory(this.getSessionIdGenerator());

		SessionManagerConfiguration<ServletContext> sessionManagerConfiguration = new org.wildfly.clustering.tomcat.SessionManagerConfiguration<>() {
			@Override
			public ServletContext getContext() {
				return servletContext;
			}

			@Override
			public ToIntFunction<ServletContext> getSessionTimeoutFunction() {
				return SESSION_TIMEOUT_FUNCTION;
			}

			@Override
			public Supplier<String> getIdentifierFactory() {
				return identifierFactory;
			}

			@Override
			public Consumer<ImmutableSession> getExpirationListener() {
				return expirationListener;
			}
		};
		SessionManager<CatalinaSessionContext, TransactionBatch> sessionManager = managerFactory.createSessionManager(sessionManagerConfiguration);

		this.manager = new DistributableManager<>(sessionManager, context, marshaller);
		this.manager.start();

		this.setState(LifecycleState.STARTING);
	}

	@Override
	protected void stopInternal() throws LifecycleException {
		this.setState(LifecycleState.STOPPING);

		Optional.ofNullable(this.manager).ifPresent(CatalinaManager::stop);

		ListIterator<Runnable> tasks = this.stopTasks.listIterator(this.stopTasks.size());
		while (tasks.hasPrevious()) {
			tasks.previous().run();
			tasks.remove();
		}

		super.stopInternal();
	}

	@Override
	public Session createSession(String sessionId) {
		return this.manager.createSession(sessionId);
	}

	@Override
	public Session findSession(String id) throws IOException {
		return this.manager.findSession(id);
	}

	@Override
	public void changeSessionId(Session session, String newId) {
		this.manager.changeSessionId(session, newId);
	}

	@Override
	public boolean willAttributeDistribute(String name, Object value) {
		return this.manager.willAttributeDistribute(name, value);
	}

	@Override
	public void load() throws ClassNotFoundException, IOException {
		// Do nothing
	}

	@Override
	public void unload() throws IOException {
		// Do nothing
	}

	@Override
	public void backgroundProcess() {
		// Do nothing
	}

	@Override
	public void processExpires() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void add(Session session) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Session createEmptySession() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Session[] findSessions() {
		// This would be super-expensive!!!
		throw new UnsupportedOperationException();
	}

	@Override
	public void remove(Session session) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void remove(Session session, boolean update) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String listSessionIds() {
		// This would be super-expensive
		throw new UnsupportedOperationException();
	}

	@Override
	public String getSessionAttribute(String sessionId, String key) {
		throw new UnsupportedOperationException();
	}

	@Override
	public HashMap<String, String> getSession(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void expireSession(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getThisAccessedTimestamp(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getThisAccessedTime(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getLastAccessedTimestamp(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getLastAccessedTime(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getCreationTime(String sessionId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getCreationTimestamp(String sessionId) {
		throw new UnsupportedOperationException();
	}
}
