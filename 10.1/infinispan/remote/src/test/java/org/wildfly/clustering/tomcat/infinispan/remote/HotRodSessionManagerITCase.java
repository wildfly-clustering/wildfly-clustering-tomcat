/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.infinispan.remote;

import org.wildfly.clustering.session.spec.container.servlet.SessionServlet;
import org.wildfly.clustering.tomcat.AbstractHotRodSessionManagerITCase;

/**
 * @author Paul Ferraro
 */
public class HotRodSessionManagerITCase extends AbstractHotRodSessionManagerITCase {

	public HotRodSessionManagerITCase() {
		super(HotRodManager.class, SessionServlet.class);
	}
}
