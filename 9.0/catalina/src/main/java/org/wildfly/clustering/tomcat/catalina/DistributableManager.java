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

package org.wildfly.clustering.tomcat.catalina;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.marshalling.spi.Marshallability;
import org.wildfly.clustering.web.session.ImmutableSession;
import org.wildfly.clustering.web.session.Session;
import org.wildfly.clustering.web.session.SessionManager;

/**
 * Adapts a WildFly distributable SessionManager to Tomcat's Manager interface.
 * @author Paul Ferraro
 */
public class DistributableManager<B extends Batch> implements CatalinaManager<B> {
    private static final char ROUTE_DELIMITER = '.';

    private final SessionManager<LocalSessionContext, B> manager;
    private final Context context;
    private final Consumer<ImmutableSession> invalidateAction;
    private final Marshallability marshallability;
    private final String route;
    private final StampedLock lifecycleLock = new StampedLock();

    // Guarded by this
    private OptionalLong lifecycleStamp = OptionalLong.empty();

    public DistributableManager(SessionManager<LocalSessionContext, B> manager, Context context, Marshallability marshallability) {
        this.manager = manager;
        this.marshallability = marshallability;
        this.context = context;
        this.route = ((Engine) context.getParent().getParent()).getJvmRoute();
        this.invalidateAction = new CatalinaSessionDestroyAction(context);

        this.manager.setDefaultMaxInactiveInterval(Duration.ofMinutes(context.getSessionTimeout()));
    }

    @Override
    public SessionManager<LocalSessionContext, B> getSessionManager() {
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

    /**
     * Appends routing information to session identifier.
     */
    private org.apache.catalina.Session getSession(Session<LocalSessionContext> session, Runnable closeTask) {
        String id = session.getId();
        String internalId = (this.route != null) ? new StringBuilder(id.length() + this.route.length() + 1).append(id).append(ROUTE_DELIMITER).append(this.route).toString() : id;
        return new DistributableSession<>(this, session, internalId, this.manager.getBatcher().suspendBatch(), () -> this.invalidateAction.accept(session), closeTask);
    }

    @Override
    public void start() {
        this.lifecycleStamp.ifPresent(stamp -> this.lifecycleLock.unlock(stamp));
        this.manager.setDefaultMaxInactiveInterval(Duration.ofMinutes(this.context.getSessionTimeout()));
        this.manager.start();
    }

    @Override
    public void stop() {
        if (!this.lifecycleStamp.isPresent()) {
            try {
                this.lifecycleStamp = OptionalLong.of(this.lifecycleLock.writeLockInterruptibly());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.manager.stop();
        }
    }

    @Override
    public org.apache.catalina.Session createSession(String sessionId) {
        String id = (sessionId != null) ? parseSessionId(sessionId) : this.manager.createIdentifier();
        Runnable closeTask = this.getSessionCloseTask();
        boolean close = true;
        try {
            Batcher<B> batcher = this.manager.getBatcher();
            // Batch will be closed by Session.close();
            B batch = batcher.createBatch();
            try {
                Session<LocalSessionContext> session = this.manager.createSession(id);
                HttpSessionEvent event = new HttpSessionEvent(CatalinaSpecificationProvider.INSTANCE.createHttpSession(session, this.context.getServletContext()));
                Stream.of(this.context.getApplicationLifecycleListeners()).filter(HttpSessionListener.class::isInstance).map(HttpSessionListener.class::cast).forEach(listener -> {
                    try {
                        this.context.fireContainerEvent("beforeSessionCreated", listener);
                        listener.sessionCreated(event);
                    } catch (Throwable e) {
                        this.context.getLogger().warn(e.getMessage(), e);
                    } finally {
                        this.context.fireContainerEvent("afterSessionCreated", listener);
                    }
                });
                org.apache.catalina.Session result = this.getSession(session, closeTask);
                close = false;
                return result;
            } catch (RuntimeException | Error e) {
                batch.discard();
                throw e;
            } finally {
                if (close) {
                    batch.close();
                }
            }
        } finally {
            if (close) {
                closeTask.run();
            }
        }
    }

    @Override
    public org.apache.catalina.Session findSession(String sessionId) throws IOException {
        String id = parseSessionId(sessionId);
        Runnable closeTask = this.getSessionCloseTask();
        boolean close = true;
        try {
            Batcher<B> batcher = this.manager.getBatcher();
            // Batch will be closed by Session.close();
            B batch = batcher.createBatch();
            try {
                Session<LocalSessionContext> session = this.manager.findSession(id);
                if (session == null) {
                    return null;
                }
                org.apache.catalina.Session result = this.getSession(session, closeTask);
                close = false;
                return result;
            } catch (RuntimeException | Error e) {
                batch.discard();
                throw e;
            } finally {
                if (close) {
                    batch.close();
                }
            }
        } finally {
            if (close) {
                closeTask.run();
            }
        }
    }

    private Runnable getSessionCloseTask() {
        StampedLock lock = this.lifecycleLock;
        long stamp = this.lifecycleLock.tryReadLock();
        if (stamp == 0L) {
            throw new IllegalStateException("Session manager was stopped");
        }
        AtomicLong stampRef = new AtomicLong(stamp);
        return new Runnable() {
            @Override
            public void run() {
                // Ensure we only unlock once.
                long stamp = stampRef.getAndSet(0L);
                if (stamp != 0L) {
                    lock.unlock(stamp);
                }
            }
        };
    }

    @Override
    public void changeSessionId(org.apache.catalina.Session session) {
        this.changeSessionId(session, this.manager.createIdentifier());
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
}
