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

	public String getAuthType() {
		return this.authType;
	}

	public void setAuthType(String authType) {
		this.authType = authType;
	}

	public Principal getPrincipal() {
		return this.principal;
	}

	public void setPrincipal(Principal principal) {
		this.principal = principal;
	}

	public Map<String, Object> getNotes() {
		return this.notes;
	}

	public List<SessionListener> getSessionListeners() {
		return this.listeners;
	}
}
