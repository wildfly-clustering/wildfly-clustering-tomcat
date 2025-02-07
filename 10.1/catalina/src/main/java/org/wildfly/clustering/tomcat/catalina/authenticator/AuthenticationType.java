/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina.authenticator;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.catalina.authenticator.Constants;

/**
 * @author Paul Ferraro
 */
public enum AuthenticationType {
	BASIC(HttpServletRequest.BASIC_AUTH),
	CLIENT_CERT(HttpServletRequest.CLIENT_CERT_AUTH),
	DIGEST(HttpServletRequest.DIGEST_AUTH),
	FORM(HttpServletRequest.FORM_AUTH),
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
