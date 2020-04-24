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

import org.apache.catalina.authenticator.Constants;

import jakarta.servlet.http.HttpServletRequest;

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
