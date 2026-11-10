/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat;

import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.wildfly.clustering.arquillian.DeploymentContainer;
import org.wildfly.clustering.session.container.SessionManagementTesterConfiguration;

/**
 * Abstract session manager integration test.
 * @author Paul Ferraro
 */
public abstract class AbstractSessionManagerITCase extends org.wildfly.clustering.session.container.AbstractSessionManagerITCase<SessionManagementArguments, WebArchive> {

	protected AbstractSessionManagerITCase(Class<?> endpointClass) {
		super(new SessionManagementTesterConfiguration() {
			@Override
			public Class<?> getEndpointClass() {
				return endpointClass;
			}
		}, WebArchive.class);
	}

	@AfterAll
	@Override
	protected void destroy() {
		this.getDeploymentContainers().stream().filter(DeploymentContainer::isStarted).forEach(DeploymentContainer::stop);
		super.destroy();
	}
}
