/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.tomcat.infinispan.remote;

import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.shrinkwrap.api.Archive;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.wildfly.clustering.session.spec.container.servlet.SessionServlet;
import org.wildfly.clustering.tomcat.AbstractHotRodSessionManagerITCase;
import org.wildfly.clustering.tomcat.SessionManagementParameters;

/**
 * @author Paul Ferraro
 */
public class HotRodSessionManagerITCase extends AbstractHotRodSessionManagerITCase {

	@ParameterizedTest(name = ParameterizedTest.ARGUMENTS_PLACEHOLDER)
	@ArgumentsSource(HotRodSessionManagerArgumentsProvider.class)
	@RunAsClient
	public void test(SessionManagementParameters parameters) throws Exception {
		Archive<?> archive = AbstractHotRodSessionManagerITCase.deployment(HotRodSessionManagerITCase.class, HotRodManager.class, parameters)
				.addPackage(this.getEndpointClass().getPackage())
				;
		this.accept(archive);
	}

	@Override
	public Class<?> getEndpointClass() {
		return SessionServlet.class;
	}
}
