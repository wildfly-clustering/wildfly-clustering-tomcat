/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import java.io.Serializable;

/**
 * @author Paul Ferraro
 */
public class Credentials implements Serializable {
	private static final long serialVersionUID = 1672356165949721516L;

	private volatile AuthenticationType authType;
	private volatile String user;
	private volatile String password;

	public AuthenticationType getAuthenticationType() {
		return this.authType;
	}

	public void setAuthenticationType(AuthenticationType authType) {
		this.authType = authType;
	}

	public String getUser() {
		return this.user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getPassword() {
		return this.password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
}
