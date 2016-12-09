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

package org.wildfly.clustering.tomcat.catalina;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;

/**
 * Provides default implementations of methods that can be derived or outright ignored.
 * @author Paul Ferraro
 */
public interface TomcatSession extends Session {

    void tellChangedSessionId(String newId, String oldId);

    @Override
    default long getCreationTimeInternal() {
        return this.getCreationTime();
    }

    @Override
    default void setCreationTime(long time) {
    }

    @Override
    default void setId(String id) {
    }

    @Override
    default void setId(String id, boolean notify) {
    }

    @Override
    default long getThisAccessedTime() {
        return this.getLastAccessedTime();
    }

    @Override
    default long getThisAccessedTimeInternal() {
        return this.getLastAccessedTime();
    }

    @Override
    default long getLastAccessedTimeInternal() {
        return this.getLastAccessedTime();
    }

    @Override
    default void setManager(Manager manager) {
    }

    @Override
    default void setNew(boolean isNew) {
    }

    @Override
    default void setValid(boolean isValid) {
    }

    @Override
    default void access() {
    }

    @Override
    default void recycle() {
    }

    @Override
    default String getInfo() {
        return null;
    }
}
