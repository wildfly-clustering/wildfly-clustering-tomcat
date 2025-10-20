/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import java.io.IOException;
import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.ServletException;

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
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.session.user.User;
import org.wildfly.clustering.session.user.UserManager;

/**
 * A distributable single sign-on implementation.
 * @author Paul Ferraro
 */
public class DistributableSingleSignOn extends SingleSignOn implements ManagerRegistry, LifecycleListener {

	private final ConcurrentMap<String, Manager> managers = new ConcurrentHashMap<>();
	private final UserManager<Credentials, TransientUserContext, String, String> manager;

	/**
	 * Creates a distributable single sign-on.
	 * @param manager a user manager
	 */
	public DistributableSingleSignOn(UserManager<Credentials, TransientUserContext, String, String> manager) {
		this.manager = manager;
	}

	@Override
	public void invoke(Request request, Response response) throws IOException, ServletException {
		try (Batch batch = this.manager.getBatchFactory().get()) {
			super.invoke(request, response);
		}
	}

	@Override
	public Manager getManager(String deployment) {
		return this.managers.get(deployment);
	}

	@Override
	protected void removeSession(String ssoId, Session session) {
		try (User<Credentials, TransientUserContext, String, String> sso = this.manager.findUser(ssoId)) {
			if (sso != null) {
				sso.getSessions().removeSession(getDeployment(session.getManager()));
				if (sso.getSessions().getDeployments().isEmpty()) {
					sso.invalidate();
				}
			}
		}
	}

	@Override
	public boolean associate(String ssoId, Session session) {
		Manager manager = session.getManager();
		String deployment = getDeployment(manager);
		try (User<Credentials, TransientUserContext, String, String> sso = this.manager.findUser(ssoId)) {
			if (sso != null) {
				sso.getSessions().addSession(deployment, session.getId());
			}
			if (this.managers.putIfAbsent(deployment, manager) == null) {
				((Lifecycle) manager).addLifecycleListener(this);
			}
			return (sso != null);
		}
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
		try (User<Credentials, TransientUserContext, String, String> sso = this.manager.findUser(ssoId)) {
			if (sso != null) {
				sso.invalidate();
			}
		}
	}

	@Override
	public void register(String ssoId, Principal principal, String authType, String username, String password) {
		Credentials credentials = new Credentials();
		credentials.setAuthenticationType(AuthenticationType.valueOf(authType));
		credentials.setUser(username);
		credentials.setPassword(password);
		try (User<Credentials, TransientUserContext, String, String> sso = this.manager.createUser(ssoId, credentials)) {
			sso.getTransientContext().setPrincipal(principal);
		}
	}

	@Override
	public boolean update(String ssoId, Principal principal, String authType, String username, String password) {
		try (User<Credentials, TransientUserContext, String, String> user = this.manager.findUser(ssoId)) {
			if (user != null) {
				user.getTransientContext().setPrincipal(principal);
				Credentials credentials = user.getPersistentContext();
				credentials.setAuthenticationType(AuthenticationType.valueOf(authType));
				credentials.setUser(username);
				credentials.setPassword(password);
				return true;
			}
		}
		return false;
	}

	private static String getDeployment(Manager manager) {
		Context context = manager.getContext();
		Host host = (Host) context.getParent();
		return host.getName() + context.getName();
	}
}
