/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
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

package org.wildfly.clustering.tomcat.catalina.authenticator;

import java.io.IOException;
import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.authenticator.SingleSignOn;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.web.sso.SSO;
import org.wildfly.clustering.web.sso.SSOManager;

import jakarta.servlet.ServletException;

/**
 * @author Paul Ferraro
 */
public class DistributableSingleSignOn extends SingleSignOn implements ManagerRegistry, LifecycleListener {

	private final ConcurrentMap<String, Manager> managers = new ConcurrentHashMap<>();
	private final SSOManager<Credentials, String, String, LocalSSOContext, Batch> manager;

	public DistributableSingleSignOn(SSOManager<Credentials, String, String, LocalSSOContext, Batch> manager) {
		this.manager = manager;
	}

	@Override
	public void invoke(Request request, Response response) throws IOException, ServletException {
		try (Batch batch = this.manager.getBatcher().createBatch()) {
			super.invoke(request, response);
		}
	}

	@Override
	public Manager getManager(String deployment) {
		return this.managers.get(deployment);
	}

	/*
	@Override
	protected void deregister(String ssoId, Session session) {
		this.removeSession(ssoId, session);
	}

	@Override
	protected SingleSignOnEntry lookup(String ssoId) {
		SSO<LocalSSOContext> sso = this.manager.findSSO(ssoId);
		return (sso != null) ? new SingleSignOnEntryFacade(sso, this) : null;
	}
*/
	@Override
	protected void removeSession(String ssoId, Session session) {
		SSO<Credentials, String, String, LocalSSOContext> sso = this.manager.findSSO(ssoId);
		if (sso != null) {
			sso.getSessions().removeSession(getDeployment(session.getManager()));
			if (sso.getSessions().getDeployments().isEmpty()) {
				sso.invalidate();
			}
		}
	}

	@Override
	public boolean associate(String ssoId, Session session) {
		Manager manager = session.getManager();
		String deployment = getDeployment(manager);
		SSO<Credentials, String, String, LocalSSOContext> sso = this.manager.findSSO(ssoId);
		if (sso != null) {
			sso.getSessions().addSession(deployment, session.getId());
		}
		if (this.managers.putIfAbsent(deployment, manager) == null) {
			((Lifecycle) manager).addLifecycleListener(this);
		}
		return (sso != null);
	}

	@Override
	public void lifecycleEvent(LifecycleEvent event) {
		String type = event.getType();
		if (Lifecycle.STOP_EVENT.equals(type)) {
			Lifecycle source = event.getLifecycle();
			Manager manager = (Manager) source;
			if (this.managers.remove(getDeployment(manager)) != null) {
				source.removeLifecycleListener(this);
			}
		}
	}

	@Override
	public void deregister(String ssoId) {
		SSO<Credentials, String, String, LocalSSOContext> sso = this.manager.findSSO(ssoId);
		if (sso != null) {
			sso.invalidate();
		}
	}

	@Override
	public void register(String ssoId, Principal principal, String authType, String username, String password) {
		Credentials credentials = new Credentials();
		credentials.setAuthenticationType(AuthenticationType.valueOf(authType));
		credentials.setUser(username);
		credentials.setPassword(password);
		SSO<Credentials, String, String, LocalSSOContext> sso = this.manager.createSSO(ssoId, credentials);
		sso.getLocalContext().setPrincipal(principal);
	}

	@Override
	public boolean update(String ssoId, Principal principal, String authType, String username, String password) {
		SSO<Credentials, String, String, LocalSSOContext> sso = this.manager.findSSO(ssoId);
		if (sso == null) return false;
		sso.getLocalContext().setPrincipal(principal);
		Credentials credentials = sso.getAuthentication();
		credentials.setAuthenticationType(AuthenticationType.valueOf(authType));
		credentials.setUser(username);
		credentials.setPassword(password);
		return true;
	}

	private static String getDeployment(Manager manager) {
		Context context = manager.getContext();
		Host host = (Host) context.getParent();
		return host.getName() + context.getName();
	}
}
