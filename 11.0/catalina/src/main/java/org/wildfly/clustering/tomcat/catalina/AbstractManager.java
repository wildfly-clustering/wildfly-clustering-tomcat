/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.io.IOException;
import java.time.Duration;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;

import org.apache.catalina.Context;
import org.apache.catalina.DistributedManager;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.session.ManagerBase;
import org.wildfly.clustering.context.Contextualizer;
import org.wildfly.clustering.context.ThreadContextClassLoaderReference;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.IntPredicate;
import org.wildfly.clustering.function.Predicate;
import org.wildfly.clustering.function.Supplier;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.marshalling.ByteBufferMarshaller;
import org.wildfly.clustering.server.immutable.Immutability;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.SessionAttributePersistenceStrategy;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.SessionManagerConfiguration;
import org.wildfly.clustering.session.SessionManagerFactory;
import org.wildfly.clustering.session.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.session.container.ContainerProvider;
import org.wildfly.clustering.session.container.servlet.ServletContainerProvider;
import org.wildfly.clustering.tomcat.SessionMarshallerFactory;
import org.wildfly.clustering.tomcat.SessionPersistenceGranularity;

/**
 * An abstract {@link org.apache.catalina.Manager}.
 * @author Paul Ferraro
 */
public abstract class AbstractManager extends ManagerBase implements DistributedManager {

	private final Deque<Runnable> stopTasks = new LinkedList<>();

	private volatile CatalinaManager manager;
	private volatile SessionAttributePersistenceStrategy persistenceStrategy = SessionPersistenceGranularity.SESSION.get();
	private volatile SessionMarshallerFactory marshallerFactory = SessionMarshallerFactory.JBOSS;
	private volatile Optional<Duration> idleTimeout = Optional.empty();
	private final Valve cookieValve = new SessionCookieValve();

	/**
	 * Creates a manager.
	 */
	protected AbstractManager() {
	}

	/**
	 * Specifies the session attribute persistence strategy of this manager.
	 * @param strategy a session attribute persistence strategy
	 */
	public void setPersistenceStrategy(SessionAttributePersistenceStrategy strategy) {
		this.persistenceStrategy = strategy;
	}

	/**
	 * Specifies the session attribute granularity of this manager.
	 * @param granularity a session attribute granularity
	 */
	public void setGranularity(SessionPersistenceGranularity granularity) {
		this.setPersistenceStrategy(granularity.get());
	}

	/**
	 * Specifies the session attribute granularity of this manager.
	 * @param granularity a session attribute granularity
	 */
	public void setGranularity(String granularity) {
		this.setGranularity(SessionPersistenceGranularity.valueOf(granularity));
	}

	/**
	 * Specifies the factory for creating the session attribute marshaller for this manager.
	 * @param marshallerFactory factory for creating the session attribute marshaller for this manager.
	 */
	public void setMarshallerFactory(SessionMarshallerFactory marshallerFactory) {
		this.marshallerFactory = marshallerFactory;
	}

	/**
	 * Specifies the name of the session attribute marshaller for this manager.
	 * @param name the name of session attribute marshaller for this manager.
	 */
	public void setMarshaller(String name) {
		this.setMarshallerFactory(SessionMarshallerFactory.valueOf(name));
	}

	/**
	 * Specifies the duration, in ISO-8601 format, following last access after which a session should be considered idle.
	 * @param duration a duration in ISO-8601 format
	 */
	public void setIdleTimeout(String duration) {
		this.idleTimeout = Optional.of(Duration.parse(duration));
	}

	/**
	 * Creates a tuple containing a session manager factory and JVM route provider.
	 * @param configuration the configuration the session manager factory
	 * @param localRoute the local JVM route
	 * @param stopTask a task to invoke on {@link AbstractManager#stop()}.
	 * @return a tuple containing a session manager factory and JVM route provider.
	 * @throws LifecycleException if the session manager factory could not be created.
	 */
	protected abstract Map.Entry<SessionManagerFactory<ServletContext, CatalinaSessionContext>, UnaryOperator<String>> createSessionManagerFactory(SessionManagerFactoryConfiguration<CatalinaSessionContext> configuration, String localRoute, Consumer<Runnable> stopTask) throws LifecycleException;

	@Override
	protected void initInternal() throws LifecycleException {
		super.initInternal();
		// Auto-add valve for re-writing session cookies
		this.getContext().getPipeline().addValve(this.cookieValve);
	}

	@Override
	protected void startInternal() throws LifecycleException {
		super.startInternal();

		Consumer<Runnable> stopTasks = this.stopTasks::addLast;
		Context context = this.getContext();
		Host host = (Host) context.getParent();
		Engine engine = (Engine) host.getParent();
		ServletContext servletContext = context.getServletContext();
		// Deployment name = host name + context path + version
		String deploymentName = host.getName() + context.getName();
		OptionalInt maxActiveSessions = IntStream.of(this.getMaxActiveSessions()).filter(IntPredicate.POSITIVE).findFirst();
		Optional<Duration> idleTimeout = this.idleTimeout;
		SessionAttributePersistenceStrategy strategy = this.persistenceStrategy;

		ClassLoader loader = context.getLoader().getClassLoader();
		ByteBufferMarshaller marshaller = this.marshallerFactory.apply(servletContext::getInitParameter, loader);

		List<Immutability> loadedImmutabilities = new LinkedList<>();
		for (Immutability loadedImmutability : ServiceLoader.load(Immutability.class, loader)) {
			loadedImmutabilities.add(loadedImmutability);
		}
		Immutability immutability = Immutability.composite(Stream.concat(Stream.of(Immutability.getDefault()), loadedImmutabilities.stream()).collect(Collectors.toList()));

		SessionManagerFactoryConfiguration<CatalinaSessionContext> sessionManagerFactoryConfig = new SessionManagerFactoryConfiguration<>() {
			@Override
			public OptionalInt getSizeThreshold() {
				return maxActiveSessions;
			}

			@Override
			public Optional<Duration> getIdleThreshold() {
				return idleTimeout;
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

			@Override
			public ClassLoader getClassLoader() {
				return loader;
			}
		};

		Map.Entry<SessionManagerFactory<ServletContext, CatalinaSessionContext>, UnaryOperator<String>> entry = this.createSessionManagerFactory(sessionManagerFactoryConfig, Objects.requireNonNull(engine.getJvmRoute()), stopTasks);
		SessionManagerFactory<ServletContext, CatalinaSessionContext> managerFactory = entry.getKey();
		UnaryOperator<String> affinity = entry.getValue();
		stopTasks.accept(managerFactory::close);

		Contextualizer contextualizer = Contextualizer.withContextProvider(ThreadContextClassLoaderReference.CURRENT.provide(context.getLoader().getClassLoader()));
		ContainerProvider<ServletContext, HttpSession, HttpSessionActivationListener, CatalinaSessionContext> provider = new ServletContainerProvider<>();
		AtomicReference<SessionManager<CatalinaSessionContext>> sessionManagerReference = new AtomicReference<>();
		Consumer<ImmutableSession> destroyNotifier = session -> CatalinaSessionEventNotifier.Lifecycle.DESTROY.accept(this, new HttpSessionEvent(provider.getDetachableSession(sessionManagerReference.getPlain(), session, this.getContext().getServletContext())));
		Supplier<String> identifierFactory = new CatalinaIdentifierFactory(this.getSessionIdGenerator());

		SessionManagerConfiguration<ServletContext> sessionManagerConfiguration = new SessionManagerConfiguration<>() {
			@Override
			public ServletContext getContext() {
				return servletContext;
			}

			@Override
			public Optional<Duration> getMaxIdle() {
				return Optional.of(Duration.ofMinutes(servletContext.getSessionTimeout())).filter(Predicate.not(Duration::isZero).and(Predicate.not(Duration::isNegative)));
			}

			@Override
			public Supplier<String> getIdentifierFactory() {
				return identifierFactory;
			}

			@Override
			public Consumer<ImmutableSession> getExpirationListener() {
				return Consumer.<ImmutableSession>empty().andThen(contextualizer.contextualize(destroyNotifier));
			}
		};
		sessionManagerReference.setPlain(managerFactory.createSessionManager(sessionManagerConfiguration));

		this.manager = new DistributableManager(new DistributableManager.Configuration() {
			@Override
			public SessionManager<CatalinaSessionContext> getSessionManager() {
				return sessionManagerReference.getPlain();
			}

			@Override
			public Predicate<Object> getMarshallability() {
				return marshaller;
			}

			@Override
			public Context getContext() {
				return context;
			}

			@Override
			public ContainerProvider<ServletContext, HttpSession, HttpSessionActivationListener, CatalinaSessionContext> getContainerProvider() {
				return provider;
			}

			@Override
			public UnaryOperator<String> getAffinity() {
				return affinity;
			}
		});
		this.manager.start();

		this.setState(LifecycleState.STARTING);
	}

	@Override
	protected void stopInternal() throws LifecycleException {
		this.setState(LifecycleState.STOPPING);

		Optional.ofNullable(this.manager).ifPresent(CatalinaManager::stop);

		Iterable<Runnable> tasks = this.stopTasks::descendingIterator;
		tasks.forEach(Runnable::run);
		this.stopTasks.clear();

		super.stopInternal();
	}

	@Override
	protected void destroyInternal() throws LifecycleException {
		this.getContext().getPipeline().removeValve(this.cookieValve);
		super.destroyInternal();
	}

	@Override
	public String rotateSessionId(Session session) {
		String id = this.manager.getSessionManager().getIdentifierFactory().get();
		this.changeSessionId(session, id);
		return session.getIdInternal();
	}

	@Override
	public Session createSession(String internalId) {
		return this.manager.createSession(Optional.ofNullable(internalId).map(AbstractManager::parseSessionId).orElseGet(this.manager.getSessionManager().getIdentifierFactory()));
	}

	@Override
	public Session findSession(String internalId) throws IOException {
		return this.manager.findSession(parseSessionId(internalId));
	}

	/**
	 * Strips routing information from requested session identifier.
	 */
	private static String parseSessionId(String internalId) {
		int index = internalId.indexOf(".");
		return (index < 0) ? internalId : internalId.substring(0, index);
	}

	@Override
	public void changeSessionId(Session session, String newId) {
		String oldId = session.getId();
		session.setId(newId);
		session.tellChangedSessionId(newId, oldId, true, true);
	}

	@Override
	public boolean willAttributeDistribute(String name, Object value) {
		return this.manager.willAttributeDistribute(name, value);
	}

	@Override
	public int getActiveSessionsFull() {
		return this.manager.getActiveSessionsFull();
	}

	@Override
	public Set<String> getSessionIdsFull() {
		return this.manager.getSessionIdsFull();
	}

	@Override
	public void load() {
		// Do nothing
	}

	@Override
	public void unload() {
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
