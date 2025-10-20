/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import java.security.Principal;

/**
 * A non-persistent user context.
 * @author Paul Ferraro
 */
class TransientUserContext {

	private volatile Principal principal;

	Principal getPrincipal() {
		return this.principal;
	}

	void setPrincipal(Principal principal) {
		this.principal = principal;
	}
}
