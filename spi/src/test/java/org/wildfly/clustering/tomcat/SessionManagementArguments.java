/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

/**
 * @author Paul Ferraro
 */
public interface SessionManagementArguments {

	SessionPersistenceGranularity getSessionPersistenceGranularity();

	SessionMarshallerFactory getSessionMarshallerFactory();
}
