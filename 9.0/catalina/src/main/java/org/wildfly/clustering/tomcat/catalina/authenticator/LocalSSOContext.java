/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import java.security.Principal;

/**
 * @author Paul Ferraro
 */
public class LocalSSOContext {

	private volatile Principal principal;

	public Principal getPrincipal() {
		return this.principal;
	}

	public void setPrincipal(Principal principal) {
		this.principal = principal;
	}
}
