/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.catalina.SessionListener;

/**
 * Local (i.e. non-persistent) context for a Tomcat session.
 * @author Paul Ferraro
 */
public class CatalinaSessionContext {
	private final Map<String, Object> notes = new ConcurrentHashMap<>();
	private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
	private volatile String authType;
	private volatile Principal principal;

	/**
	 * Creates the context of a Tomcat session.
	 */
	public CatalinaSessionContext() {
	}

	/**
	 * Returns the authentication type.
	 * @return the authentication type.
	 */
	public String getAuthType() {
		return this.authType;
	}

	/**
	 * Specifies the authentication type.
	 * @param authType the authentication type.
	 */
	public void setAuthType(String authType) {
		this.authType = authType;
	}

	/**
	 * Returns the user principal.
	 * @return the user principal.
	 */
	public Principal getPrincipal() {
		return this.principal;
	}

	/**
	 * Specifies the user principal.
	 * @param principal a user principal
	 */
	public void setPrincipal(Principal principal) {
		this.principal = principal;
	}

	/**
	 * Returns the notes of this session.
	 * @return the notes of this session.
	 */
	public Map<String, Object> getNotes() {
		return this.notes;
	}

	/**
	 * Returns the listeners of this session.
	 * @return the listeners of this session.
	 */
	public List<SessionListener> getSessionListeners() {
		return this.listeners;
	}
}
