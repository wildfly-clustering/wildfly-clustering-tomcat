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

import java.beans.PropertyChangeListener;
import java.io.IOException;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Manager;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.marshalling.spi.Marshallability;
import org.wildfly.clustering.web.session.SessionManager;

/**
 * Enhances Tomcat's Manager interface, providing default implementations for deprecated methods and methods we currently ignore.
 * @author Paul Ferraro
 */
public interface TomcatManager extends Manager, Lifecycle {

    /**
     * Returns underlying distributable session manager implementation.
     * @return a session manager
     */
    SessionManager<LocalSessionContext, Batch> getSessionManager();

    /**
     * Returns a mechanism for determining the marshallability of a session attribute.
     * @return
     */
    Marshallability getMarshallability();

    /**
     * Returns the context of this manager.
     * @return the context of this manager.
     */
    Context getContext();

    @Override
    default Container getContainer() {
        return this.getContext();
    }

    // We don't care about any of the methods below

    @Override
    default void init() throws LifecycleException {
    }

    @Override
    default void destroy() throws LifecycleException {
    }

    @Override
    default void setContainer(Container container) {
        // Ignore
    }

    @Override
    default long getSessionCounter() {
        return 0;
    }

    @Override
    default void setSessionCounter(long sessionCounter) {
    }

    @Override
    default int getMaxActive() {
        return 0;
    }

    @Override
    default void setMaxActive(int maxActive) {
    }

    @Override
    default int getActiveSessions() {
        return 0;
    }

    @Override
    default long getExpiredSessions() {
        return 0;
    }

    @Override
    default void setExpiredSessions(long expiredSessions) {
    }

    @Override
    default int getRejectedSessions() {
        return 0;
    }

    @Override
    default int getSessionMaxAliveTime() {
        return 0;
    }

    @Override
    default void setSessionMaxAliveTime(int sessionMaxAliveTime) {
    }

    @Override
    default int getSessionAverageAliveTime() {
        return 0;
    }

    @Override
    default int getSessionCreateRate() {
        return 0;
    }

    @Override
    default int getSessionExpireRate() {
        return 0;
    }

    @Override
    default void add(org.apache.catalina.Session session) {
    }

    @Override
    default void addPropertyChangeListener(PropertyChangeListener listener) {
    }

    @Override
    default org.apache.catalina.Session createEmptySession() {
        return null;
    }

    @Override
    default org.apache.catalina.Session[] findSessions() {
        return null;
    }

    @Override
    default void load() throws ClassNotFoundException, IOException {
    }

    @Override
    default void remove(org.apache.catalina.Session session) {
    }

    @Override
    default void remove(org.apache.catalina.Session session, boolean update) {
    }

    @Override
    default void removePropertyChangeListener(PropertyChangeListener listener) {
    }

    @Override
    default void unload() throws IOException {
    }

    @Override
    default void backgroundProcess() {
    }

    @Override
    default void addLifecycleListener(LifecycleListener listener) {
    }

    @Override
    default LifecycleListener[] findLifecycleListeners() {
        return null;
    }

    @Override
    default void removeLifecycleListener(LifecycleListener listener) {
    }

    @Override
    default LifecycleState getState() {
        return null;
    }

    @Override
    default String getStateName() {
        return null;
    }

    @Override
    default int getSessionIdLength() {
        return 0;
    }

    @Override
    default void setSessionIdLength(int length) {
    }

    @Override
    default String getInfo() {
        return null;
    }

    // Provide default impls for deprecated methods

    @Deprecated
    @Override
    default boolean getDistributable() {
        return this.getContext().getDistributable();
    }

    @Deprecated
    @Override
    default void setDistributable(boolean distributable) {
        // Ignore
    }

    @Deprecated
    @Override
    default int getMaxInactiveInterval() {
        return this.getContext().getSessionTimeout() * 60;
    }

    @Deprecated
    @Override
    default void setMaxInactiveInterval(int interval) {
        // Ignore
    }
}
