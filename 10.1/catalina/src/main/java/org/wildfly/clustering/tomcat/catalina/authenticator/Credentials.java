/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import java.io.Serializable;

/**
 * Serializable object that encapsulates the credentials of a user.
 * @author Paul Ferraro
 */
class Credentials implements Serializable {
	private static final long serialVersionUID = 1672356165949721516L;

	private volatile AuthenticationType authType;
	private volatile String user;
	private volatile String password;

	AuthenticationType getAuthenticationType() {
		return this.authType;
	}

	void setAuthenticationType(AuthenticationType authType) {
		this.authType = authType;
	}

	String getUser() {
		return this.user;
	}

	void setUser(String user) {
		this.user = user;
	}

	String getPassword() {
		return this.password;
	}

	void setPassword(String password) {
		this.password = password;
	}
}
