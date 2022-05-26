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
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.wildfly.clustering.marshalling.Externalizer;
import org.wildfly.clustering.marshalling.spi.EnumExternalizer;

/**
 * @author Paul Ferraro
 */
public class CredentialsExternalizer implements Externalizer<Credentials> {
    private static final EnumExternalizer<AuthenticationType> AUTH_TYPE_EXTERNALIZER = new EnumExternalizer<>(AuthenticationType.class);

    @Override
    public void writeObject(ObjectOutput output, Credentials credentials) throws IOException {
        AUTH_TYPE_EXTERNALIZER.writeObject(output, credentials.getAuthenticationType());
        output.writeUTF(credentials.getUser());
        output.writeUTF(credentials.getPassword());
    }

    @Override
    public Credentials readObject(ObjectInput input) throws IOException, ClassNotFoundException {
        Credentials credentials = new Credentials();
        credentials.setAuthenticationType(AUTH_TYPE_EXTERNALIZER.readObject(input));
        credentials.setUser(input.readUTF());
        credentials.setPassword(input.readUTF());
        return credentials;
    }

    @Override
    public Class<Credentials> getTargetClass() {
        return Credentials.class;
    }
}
