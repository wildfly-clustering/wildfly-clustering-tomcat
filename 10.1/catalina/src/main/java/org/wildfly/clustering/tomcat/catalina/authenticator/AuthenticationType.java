/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.catalina.authenticator.Constants;

/**
 * Enumeration of standard authentication types.
 * @author Paul Ferraro
 */
public enum AuthenticationType {
	/** BASIC authentication */
	BASIC(HttpServletRequest.BASIC_AUTH),
	/** CLIENT_CERT authentication */
	CLIENT_CERT(HttpServletRequest.CLIENT_CERT_AUTH),
	/** DIGEST authentication */
	DIGEST(HttpServletRequest.DIGEST_AUTH),
	/** FORM authentication */
	FORM(HttpServletRequest.FORM_AUTH),
	/** SPNEGO authentication */
	SPNEGO(Constants.SPNEGO_METHOD),
	;
	private String name;

	AuthenticationType(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return this.name;
	}
}
