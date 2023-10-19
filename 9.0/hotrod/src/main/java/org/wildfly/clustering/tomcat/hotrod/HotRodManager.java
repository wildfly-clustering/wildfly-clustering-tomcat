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

package org.wildfly.clustering.tomcat.hotrod;

import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;

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
import org.wildfly.clustering.ee.Immutability;
import org.wildfly.clustering.ee.cache.tx.TransactionBatch;
import org.wildfly.clustering.ee.immutable.CompositeImmutability;
import org.wildfly.clustering.ee.immutable.DefaultImmutability;
import org.wildfly.clustering.infinispan.marshalling.protostream.ProtoStreamMarshaller;
import org.wildfly.clustering.marshalling.protostream.SimpleClassLoaderMarshaller;
import org.wildfly.clustering.marshalling.spi.ByteBufferMarshaller;
import org.wildfly.clustering.tomcat.SessionMarshallerFactory;
import org.wildfly.clustering.tomcat.SessionPersistenceGranularity;
import org.wildfly.clustering.tomcat.catalina.CatalinaManager;
import org.wildfly.clustering.tomcat.catalina.CatalinaSessionExpirationListener;
import org.wildfly.clustering.tomcat.catalina.CatalinaSpecificationProvider;
import org.wildfly.clustering.tomcat.catalina.DistributableManager;
import org.wildfly.clustering.tomcat.catalina.CatalinaIdentifierFactory;
import org.wildfly.clustering.tomcat.catalina.LocalSessionContext;
import org.wildfly.clustering.web.hotrod.session.HotRodSessionManagerFactory;
import org.wildfly.clustering.web.hotrod.session.HotRodSessionManagerFactoryConfiguration;
import org.wildfly.clustering.web.session.ImmutableSession;
import org.wildfly.clustering.web.session.SessionAttributeImmutability;
import org.wildfly.clustering.web.session.SessionAttributePersistenceStrategy;
import org.wildfly.clustering.web.session.SessionManager;
import org.wildfly.clustering.web.session.SessionManagerConfiguration;
import org.wildfly.clustering.web.session.SessionManagerFactory;
import org.wildfly.clustering.web.session.SpecificationProvider;
import org.wildfly.common.iteration.CompositeIterable;
import org.wildfly.security.manager.WildFlySecurityManager;

/**
 * Distributed Manager implementation that configures a HotRod client.
 * @author Paul Ferraro
 */
public class HotRodManager extends ManagerBase {

	static final ToIntFunction<ServletContext> SESSION_TIMEOUT_FUNCTION = ServletContext::getSessionTimeout;

	private final Properties properties = new Properties();

	private volatile RemoteCacheContainer container;
	private volatile SessionManagerFactory<ServletContext, LocalSessionContext, TransactionBatch> managerFactory;
	private volatile CatalinaManager<TransactionBatch> manager;
	private volatile SessionAttributePersistenceStrategy persistenceStrategy = SessionAttributePersistenceStrategy.COARSE;
	private volatile SessionMarshallerFactory marshallerFactory = SessionMarshallerFactory.JBOSS;
	private volatile String templateName = DefaultTemplate.DIST_SYNC.getTemplateName();
	private volatile URI uri = null;
	private volatile int expirationThreadPoolSize = 16;

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

	public void setExpirationThreadPoolSize(int size) {
		this.expirationThreadPoolSize = size;
	}

	@Deprecated
	public void setPersistenceStrategy(String strategy) {
		this.setPersistenceStrategy(SessionAttributePersistenceStrategy.valueOf(strategy));
	}

	@Deprecated
	public void setConfigurationName(String configurationName) {
		this.setTemplate(configurationName);
	}

	@Override
	protected void startInternal() throws LifecycleException {
		super.startInternal();

		Context context = this.getContext();
		Host host = (Host) context.getParent();
		Engine engine = (Engine) host.getParent();
		// Deployment name = host name + context path + version
		String deploymentName = host.getName() + context.getName();
		Integer maxActiveSessions = (this.getMaxActiveSessions() >= 0) ? Integer.valueOf(this.getMaxActiveSessions()) : null;
		SessionAttributePersistenceStrategy strategy = this.persistenceStrategy;

		ClassLoader containerLoader = WildFlySecurityManager.getClassLoaderPrivileged(HotRodSessionManagerFactory.class);
		Configuration configuration = Optional.ofNullable(this.uri).map(HotRodURI::create).map(HotRodURI::toConfigurationBuilder).orElseGet(ConfigurationBuilder::new)
				.withProperties(this.properties)
				.marshaller(new ProtoStreamMarshaller(new SimpleClassLoaderMarshaller(containerLoader), builder -> builder.load(containerLoader)))
				.classLoader(containerLoader)
				.build();

		configuration.addRemoteCache(deploymentName, builder -> builder.forceReturnValues(false).nearCacheMode(maxActiveSessions != null ? NearCacheMode.INVALIDATED : NearCacheMode.DISABLED).transactionMode(TransactionMode.NONE).templateName(this.templateName));

		RemoteCacheContainer container = new RemoteCacheManager(configuration);
		container.start();
		this.container = container;

		ClassLoader loader = context.getLoader().getClassLoader();
		ByteBufferMarshaller marshaller = this.marshallerFactory.apply(loader);

		List<Immutability> loadedImmutabilities = new LinkedList<>();
		for (Immutability loadedImmutability : ServiceLoader.load(Immutability.class, loader)) {
			loadedImmutabilities.add(loadedImmutability);
		}
		Immutability immutability = new CompositeImmutability(new CompositeIterable<>(EnumSet.allOf(DefaultImmutability.class), EnumSet.allOf(SessionAttributeImmutability.class), loadedImmutabilities));
		int expirationThreadPoolSize = this.expirationThreadPoolSize;

		HotRodSessionManagerFactoryConfiguration<HttpSession, ServletContext, HttpSessionActivationListener, LocalSessionContext> sessionManagerFactoryConfig = new HotRodSessionManagerFactoryConfiguration<>() {
			@Override
			public Integer getMaxActiveSessions() {
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
			public Supplier<LocalSessionContext> getLocalContextFactory() {
				return LocalSessionContext::new;
			}

			@Override
			public <K, V> RemoteCache<K, V> getCache() {
				return container.getCache(this.getDeploymentName());
			}

			@Override
			public Immutability getImmutability() {
				return immutability;
			}

			@Override
			public SpecificationProvider<HttpSession, ServletContext, HttpSessionActivationListener> getSpecificationProvider() {
				return CatalinaSpecificationProvider.INSTANCE;
			}

			@Override
			public int getExpirationThreadPoolSize() {
				return expirationThreadPoolSize;
			}
		};

		this.managerFactory = new HotRodSessionManagerFactory<>(sessionManagerFactoryConfig);

		ServletContext servletContext = context.getServletContext();
		Consumer<ImmutableSession> expirationListener = new CatalinaSessionExpirationListener(context);
		Supplier<String> identifierFactory = new CatalinaIdentifierFactory(this.getSessionIdGenerator());

		SessionManagerConfiguration<ServletContext> sessionManagerConfiguration = new org.wildfly.clustering.tomcat.SessionManagerConfiguration<>() {
			@Override
			public ServletContext getServletContext() {
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
		SessionManager<LocalSessionContext, TransactionBatch> sessionManager = this.managerFactory.createSessionManager(sessionManagerConfiguration);

		this.manager = new DistributableManager<>(sessionManager, context, marshaller);
		this.manager.start();

		this.setState(LifecycleState.STARTING);
	}

	@Override
	protected void stopInternal() throws LifecycleException {
		this.setState(LifecycleState.STOPPING);

		Optional.ofNullable(this.manager).ifPresent(CatalinaManager::stop);
		Optional.ofNullable(this.managerFactory).ifPresent(SessionManagerFactory::close);
		Optional.ofNullable(this.container).ifPresent(RemoteCacheContainer::stop);
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
	public void changeSessionId(Session session) {
		this.manager.changeSessionId(session, this.generateSessionId());
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
