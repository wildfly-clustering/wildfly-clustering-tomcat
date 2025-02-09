/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

/**
 * @author Paul Ferraro
 */
public interface SessionManagementParameters {

	default SessionPersistenceGranularity getSessionPersistenceGranularity() {
		return SessionPersistenceGranularity.ATTRIBUTE;
	}

	default SessionMarshallerFactory getSessionMarshallerFactory() {
		return SessionMarshallerFactory.PROTOSTREAM;
	}
}
