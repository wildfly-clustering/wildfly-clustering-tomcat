/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;

import javax.servlet.http.HttpSession;

/**
 * Implements deprecated methods, as well as {@link #equals(Object)}, {@link #hashCode()}, and {@link #toString()}.
 * @author Paul Ferraro
 */
public abstract class AbstractHttpSession implements HttpSession {
	/**
	 * Creates a session.
	 */
	protected AbstractHttpSession() {
	}

	@Deprecated
	@Override
	public String[] getValueNames() {
		return Collections.list(this.getAttributeNames()).toArray(new String[0]);
	}

	@Deprecated
	@Override
	public Object getValue(String name) {
		return this.getAttribute(name);
	}

	@Deprecated
	@Override
	public void putValue(String name, Object value) {
		this.setAttribute(name, value);
	}

	@Deprecated
	@Override
	public void removeValue(String name) {
		this.removeAttribute(name);
	}

	@Deprecated
	@Override
	public javax.servlet.http.HttpSessionContext getSessionContext() {
		return new javax.servlet.http.HttpSessionContext() {
			@Override
			public Enumeration<String> getIds() {
				return Collections.enumeration(Collections.<String>emptyList());
			}

			@Override
			public HttpSession getSession(String sessionId) {
				return null;
			}
		};
	}

	@Override
	public int hashCode() {
		return this.getId().hashCode();
	}

	@Override
	public boolean equals(Object object) {
		if (!(object instanceof HttpSession)) return false;
		HttpSession session = (HttpSession) object;
		return Objects.equals(this.getId(), session.getId()) && Objects.equals(this.getServletContext().getVirtualServerName(), session.getServletContext().getVirtualServerName()) && Objects.equals(this.getServletContext().getContextPath(), session.getServletContext().getContextPath());
	}

	@Override
	public String toString() {
		return this.getId();
	}
}
