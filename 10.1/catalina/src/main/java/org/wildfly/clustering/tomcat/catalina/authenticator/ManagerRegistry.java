/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import org.apache.catalina.Manager;

/**
 * Mechanism for looking up the {@link Manager} of a given deployment.
 * @author Paul Ferraro
 */
public interface ManagerRegistry {
	/**
	 * Returns the session manager for the specified deployment, or null if the deployment does not exist.
	 * @param deployment a deployment name
	 * @return a session manager
	 */
	Manager getManager(String deployment);
}
