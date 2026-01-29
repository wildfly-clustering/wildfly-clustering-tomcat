/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.tomcat.catalina;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import javax.servlet.ServletException;
import javax.servlet.SessionTrackingMode;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.ApplicationSessionCookieConfig;
import org.apache.catalina.valves.ValveBase;

/**
 * A valve that adds a session cookie to the response if the internal session identifier has changed.
 * @author Paul Ferraro
 */
public class SessionCookieValve extends ValveBase {
	private static final VarHandle SESSION = findSessionField();

	private static VarHandle findSessionField() {
		try {
			return MethodHandles.privateLookupIn(Request.class, MethodHandles.lookup()).findVarHandle(Request.class, "session", Session.class);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Creates a new valve */
	public SessionCookieValve() {
	}

	@Override
	public void invoke(Request request, Response response) throws IOException, ServletException {
		try {
			this.next.invoke(request, response);
		} finally {
			if (!response.isCommitted() && request.getServletContext().getEffectiveSessionTrackingModes().contains(SessionTrackingMode.COOKIE)) {
				String requestedSessionId = request.getRequestedSessionId();
				if (requestedSessionId != null) {
					// Use session referenced within request
					Session session = (Session) SESSION.get(request);
					if (session != null) {
						String sessionId = session.getIdInternal();
						if (!sessionId.equals(requestedSessionId)) {
							response.addCookie(ApplicationSessionCookieConfig.createSessionCookie(request.getContext(), sessionId, request.isSecure()));
						}
					}
				}
			}
		}
	}
}
