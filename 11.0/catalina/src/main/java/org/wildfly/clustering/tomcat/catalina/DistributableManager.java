/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import jakarta.servlet.http.HttpSessionEvent;

import org.apache.catalina.Context;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.BatchContext;
import org.wildfly.clustering.cache.batch.SuspendedBatch;
import org.wildfly.clustering.marshalling.Marshallability;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;

/**
 * Adapts a WildFly distributable SessionManager to Tomcat's Manager interface.
 * @author Paul Ferraro
 */
public class DistributableManager implements CatalinaManager {
	private static final System.Logger LOGGER = System.getLogger(DistributableManager.class.getPackageName());
	private static final char ROUTE_DELIMITER = '.';

	private final SessionManager<CatalinaSessionContext> manager;
	private final UnaryOperator<String> affinity;
	private final Context context;
	private final Marshallability marshallability;
	private final StampedLock lifecycleLock = new StampedLock();
	private final AtomicLong lifecycleStamp = new AtomicLong();

	public DistributableManager(SessionManager<CatalinaSessionContext> manager, UnaryOperator<String> affinity, Context context, Marshallability marshallability) {
		this.manager = manager;
		this.affinity = affinity;
		this.marshallability = marshallability;
		this.context = context;
	}

	@Override
	public SessionManager<CatalinaSessionContext> getSessionManager() {
		return this.manager;
	}

	@Override
	public Marshallability getMarshallability() {
		return this.marshallability;
	}

	/**
	 * Strips routing information from requested session identifier.
	 */
	private static String parseSessionId(String requestedSesssionId) {
		int index = requestedSesssionId.indexOf(ROUTE_DELIMITER);
		return (index < 0) ? requestedSesssionId : requestedSesssionId.substring(0, index);
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
	public org.apache.catalina.Session createSession(String sessionId) {
		String id = (sessionId != null) ? parseSessionId(sessionId) : this.manager.getIdentifierFactory().get();
		return this.getSession(SessionManager::createSession, id);
	}

	@Override
	public org.apache.catalina.Session findSession(String sessionId) throws IOException {
		String id = parseSessionId(sessionId);
		return this.getSession(SessionManager::findSession, id);
	}

	private org.apache.catalina.Session getSession(BiFunction<SessionManager<CatalinaSessionContext>, String, Session<CatalinaSessionContext>> function, String id) {
		Map.Entry<SuspendedBatch, Runnable> entry = this.createBatchEntry();
		SuspendedBatch suspendedBatch = entry.getKey();
		Runnable closeTask = entry.getValue();
		try (BatchContext<Batch> context = suspendedBatch.resumeWithContext()) {
			Session<CatalinaSessionContext> session = function.apply(this.manager, id);
			if ((session == null) || !session.isValid() || session.getMetaData().isExpired()) {
				rollback(context, closeTask);
				return null;
			}
			if (session.getMetaData().isNew()) {
				HttpSessionEvent event = new HttpSessionEvent(HttpSessionProvider.INSTANCE.asSession(session, this.getContext().getServletContext()));
				CatalinaSessionEventNotifier.Lifecycle.CREATE.accept(this, event);
			}
			String route = this.affinity.apply(id);
			// Append route to session identifier.
			String internalId = new StringBuilder(id.length() + route.length() + 1).append(id).append(ROUTE_DELIMITER).append(route).toString();
			return new DistributableSession(this, session, internalId, suspendedBatch, closeTask);
		} catch (RuntimeException | Error e) {
			rollback(suspendedBatch::resume, closeTask);
			throw e;
		}
	}

	@Override
	public void changeSessionId(org.apache.catalina.Session session, String id) {
		session.tellChangedSessionId(id, session.getId(), true, true);
	}

	@Override
	public Context getContext() {
		return this.context;
	}

	@Override
	public boolean willAttributeDistribute(String name, Object value) {
		return this.marshallability.isMarshallable(value);
	}

	@Override
	public boolean getNotifyAttributeListenerOnUnchangedValue() {
		return false;
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

	private static void rollback(Supplier<Batch> batchSupplier, Runnable closeTask) {
		try (Batch batch = batchSupplier.get()) {
			batch.discard();
		} catch (RuntimeException | Error e) {
			LOGGER.log(System.Logger.Level.WARNING, e.getLocalizedMessage(), e);
		} finally {
			closeTask.run();
		}
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
