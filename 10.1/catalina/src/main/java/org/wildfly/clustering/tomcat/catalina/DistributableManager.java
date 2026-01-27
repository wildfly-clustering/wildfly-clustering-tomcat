/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionEvent;

import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.SuspendedBatch;
import org.wildfly.clustering.context.Context;
import org.wildfly.clustering.function.BiFunction;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.Predicate;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.container.ContainerProvider;

/**
 * Adapts a WildFly distributable SessionManager to Tomcat's Manager interface.
 * @author Paul Ferraro
 */
public class DistributableManager implements CatalinaManager {
	private static final System.Logger LOGGER = System.getLogger(DistributableManager.class.getCanonicalName());
	private static final char ROUTE_DELIMITER = '.';

	interface Configuration {
		SessionManager<CatalinaSessionContext> getSessionManager();
		ContainerProvider<ServletContext, HttpSession, HttpSessionActivationListener, CatalinaSessionContext> getContainerProvider();
		UnaryOperator<String> getAffinity();
		org.apache.catalina.Context getContext();
		Predicate<Object> getMarshallability();
	}

	private final ContainerProvider<ServletContext, HttpSession, HttpSessionActivationListener, CatalinaSessionContext> provider;
	private final SessionManager<CatalinaSessionContext> manager;
	private final UnaryOperator<String> internalizer;
	private final org.apache.catalina.Context context;
	private final Predicate<Object> marshallability;
	private final StampedLock lifecycleLock = new StampedLock();
	private final AtomicLong lifecycleStamp = new AtomicLong();

	/**
	 * Creates a distributed manager.
	 * @param configuration the configuration of this manager
	 */
	public DistributableManager(Configuration configuration) {
		this.manager = configuration.getSessionManager();
		this.provider = configuration.getContainerProvider();
		this.internalizer = new UnaryOperator<>() {
			@Override
			public String apply(String id) {
				String route = configuration.getAffinity().apply(id);
				return (route != null) ? new StringBuilder(id.length() + route.length() + 1).append(id).append(ROUTE_DELIMITER).append(route).toString() : id;
			}
		};
		this.marshallability = configuration.getMarshallability();
		this.context = configuration.getContext();
	}

	@Override
	public SessionManager<CatalinaSessionContext> getSessionManager() {
		return this.manager;
	}

	@Override
	public UnaryOperator<String> getIdentifierInternalizer() {
		return this.internalizer;
	}

	@Override
	public ContainerProvider<ServletContext, HttpSession, HttpSessionActivationListener, CatalinaSessionContext> getContainerProvider() {
		return this.provider;
	}

	@Override
	public Predicate<Object> getMarshallability() {
		return this.marshallability;
	}

	@Override
	public void start() {
		this.manager.start();
		long stamp = this.lifecycleStamp.getAndSet(0L);
		if (StampedLock.isWriteLockStamp(stamp)) {
			this.lifecycleLock.unlockWrite(stamp);
		}
	}

	@Override
	public void stop() {
		try {
			this.lifecycleStamp.set(this.lifecycleLock.writeLockInterruptibly());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		this.manager.stop();
	}

	@Override
	public org.apache.catalina.Session createSession(String id) {
		LOGGER.log(System.Logger.Level.TRACE, "DistributableManager.createSession({0})", id);
		return this.getSession(SessionManager::createSession, id);
	}

	@Override
	public org.apache.catalina.Session findSession(String id) {
		LOGGER.log(System.Logger.Level.TRACE, "DistributableManager.findSession({0})", id);
		return this.getSession(SessionManager::findSession, id);
	}

	private org.apache.catalina.Session getSession(BiFunction<SessionManager<CatalinaSessionContext>, String, Session<CatalinaSessionContext>> function, String id) {
		Map.Entry<SuspendedBatch, Runnable> entry = this.createBatchEntry();
		SuspendedBatch suspendedBatch = entry.getKey();
		Runnable closeTask = entry.getValue();
		try (Context<Batch> context = suspendedBatch.resumeWithContext()) {
			Session<CatalinaSessionContext> session = function.apply(this.manager, id);
			if (session == null) {
				LOGGER.log(System.Logger.Level.TRACE, "Session {0} not found", id);
				return close(context, closeTask);
			}
			if (!session.isValid()) {
				LOGGER.log(System.Logger.Level.DEBUG, "Session {0} found, but is not valid.", id);
				return close(context, closeTask);
			}
			if (session.getMetaData().isExpired()) {
				LOGGER.log(System.Logger.Level.DEBUG, "Session {0} found, but has expired.", id);
				return close(context, closeTask);
			}
			if (session.getMetaData().getLastAccessTime().isEmpty()) {
				HttpSessionEvent event = new HttpSessionEvent(this.getContainerProvider().getDetachableSession(this.getSessionManager(), session, this.getContext().getServletContext()));
				CatalinaSessionEventNotifier.Lifecycle.CREATE.accept(this, event);
			}
			return new DistributableSession(this, session, suspendedBatch, closeTask);
		} catch (RuntimeException | Error e) {
			try (Context<Batch> context = entry.getKey().resumeWithContext()) {
				close(context, Batch::discard, entry.getValue());
			}
			throw e;
		}
	}

	@Override
	public org.apache.catalina.Context getContext() {
		return this.context;
	}

	@Override
	public boolean willAttributeDistribute(String name, Object value) {
		return this.marshallability.test(value);
	}

	@Override
	public boolean getNotifyAttributeListenerOnUnchangedValue() {
		return false;
	}

	@Override
	public int hashCode() {
		return this.getContext().hashCode();
	}

	@Override
	public boolean equals(Object object) {
		return (object instanceof CatalinaManager manager) ? this.getContext().equals(manager.getContext()) : false;
	}

	@Override
	public String toString() {
		return this.getContext().toString();
	}

	private Map.Entry<SuspendedBatch, Runnable> createBatchEntry() {
		Runnable closeTask = this.getSessionCloseTask();
		try {
			return Map.entry(this.manager.getBatchFactory().get().suspend(), closeTask);
		} catch (RuntimeException | Error e) {
			closeTask.run();
			throw e;
		}
	}

	private static org.apache.catalina.Session close(Supplier<Batch> batchProvider, Runnable closeTask) {
		return close(batchProvider, Consumer.empty(), closeTask);
	}

	private static org.apache.catalina.Session close(Supplier<Batch> batchProvider, Consumer<Batch> batchTask, Runnable closeTask) {
		try (Batch batch = batchProvider.get()) {
			batch.discard();
		} catch (RuntimeException | Error e) {
			LOGGER.log(System.Logger.Level.WARNING, e.getLocalizedMessage(), e);
		} finally {
			closeTask.run();
		}
		return null;
	}

	private Runnable getSessionCloseTask() {
		StampedLock lock = this.lifecycleLock;
		long stamp = lock.tryReadLock();
		if (!StampedLock.isReadLockStamp(stamp)) {
			throw new IllegalStateException();
		}
		AtomicLong stampRef = new AtomicLong(stamp);
		return new Runnable() {
			@Override
			public void run() {
				// Ensure we only unlock once.
				long stamp = stampRef.getAndSet(0L);
				if (StampedLock.isReadLockStamp(stamp)) {
					lock.unlockRead(stamp);
				}
			}
		};
	}
}
