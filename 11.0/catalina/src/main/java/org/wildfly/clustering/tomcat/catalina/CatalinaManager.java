/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.beans.PropertyChangeListener;
import java.util.Set;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;

import org.apache.catalina.Context;
import org.apache.catalina.DistributedManager;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.SessionIdGenerator;
import org.wildfly.clustering.function.Predicate;
import org.wildfly.clustering.function.UnaryOperator;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.container.ContainerProvider;

/**
 * Enhances Tomcat's Manager interface, providing default implementations for deprecated methods and methods we currently ignore.
 * @author Paul Ferraro
 */
public interface CatalinaManager extends Manager, Lifecycle, DistributedManager {

	/**
	 * Returns underlying distributable session manager implementation.
	 * @return a session manager
	 */
	SessionManager<CatalinaSessionContext> getSessionManager();

	/**
	 * Returns a function for computing the internal session identifier.
	 * @return a function for computing the internal session identifier.
	 */
	UnaryOperator<String> getIdentifierInternalizer();

	/**
	 * Returns the container provider.
	 * @return the container provider.
	 */
	ContainerProvider<ServletContext, HttpSession, HttpSessionActivationListener, CatalinaSessionContext> getContainerProvider();

	/**
	 * Returns a predicate for testing the marshallability of a session attribute.
	 * @return a predicate for testing the marshallability of a session attribute.
	 */
	Predicate<Object> getMarshallability();

	@Override
	default int getActiveSessionsFull() {
		return (int) this.getSessionManager().getStatistics().getActiveSessionCount();
	}

	@Override
	default Set<String> getSessionIdsFull() {
		return this.getSessionManager().getStatistics().getActiveSessions();
	}

	@Override
	void start();

	@Override
	void stop();

	// We don't care about any of the methods below

	@Override
	default void changeSessionId(Session session, String newId) {
	}

	@Override
	default void init() {
	}

	@Override
	default void destroy() {
	}

	@Override
	default void setContext(Context context) {
	}

	@Override
	default SessionIdGenerator getSessionIdGenerator() {
		return null;
	}

	@Override
	default void setSessionIdGenerator(SessionIdGenerator sessionIdGenerator) {
	}

	@Override
	default long getSessionCounter() {
		return 0;
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
	default void load() {
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
	default void unload() {
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
	default void setNotifyBindingListenerOnUnchangedValue(boolean notifyBindingListenerOnUnchangedValue) {
	}

	@Override
	default void setNotifyAttributeListenerOnUnchangedValue(boolean notifyAttributeListenerOnUnchangedValue) {
	}

	@Override
	default void setSessionActivityCheck(boolean sessionActivityCheck) {
	}

	@Override
	default void setSessionLastAccessAtStart(boolean sessionLastAccessAtStart) {
	}
}
