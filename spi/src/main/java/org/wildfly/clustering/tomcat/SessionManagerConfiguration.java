/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat;

import java.time.Duration;
import java.util.function.ToIntFunction;

/**
 * @author Paul Ferraro
 */
public interface SessionManagerConfiguration<SC> extends org.wildfly.clustering.session.SessionManagerConfiguration<SC> {

	ToIntFunction<SC> getSessionTimeoutFunction();

	@Override
	default Duration getTimeout() {
		return Duration.ofMinutes(this.getSessionTimeoutFunction().applyAsInt(this.getContext()));
	}
}
