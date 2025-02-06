/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat.infinispan.embedded;

import org.wildfly.clustering.session.spec.container.servlet.SessionServlet;
import org.wildfly.clustering.tomcat.AbstractInfinispanSessionManagerITCase;

/**
 * @author Paul Ferraro
 */
public class InfinispanSessionManagerITCase extends AbstractInfinispanSessionManagerITCase {

	public InfinispanSessionManagerITCase() {
		super(InfinispanManager.class, SessionServlet.class);
	}
}
