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

package org.wildfly.clustering.tomcat.hotrod;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Properties;
import java.util.function.Function;

import javax.servlet.ServletContext;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.NearCacheMode;
import org.infinispan.commons.marshall.jboss.DefaultContextClassResolver;
import org.jboss.marshalling.MarshallingConfiguration;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.Recordable;
import org.wildfly.clustering.marshalling.jboss.ExternalizerObjectTable;
import org.wildfly.clustering.marshalling.jboss.MarshallingContext;
import org.wildfly.clustering.marshalling.jboss.SimpleClassTable;
import org.wildfly.clustering.marshalling.jboss.SimpleMarshalledValueFactory;
import org.wildfly.clustering.marshalling.jboss.SimpleMarshallingConfigurationRepository;
import org.wildfly.clustering.marshalling.jboss.SimpleMarshallingContextFactory;
import org.wildfly.clustering.marshalling.spi.MarshalledValueFactory;
import org.wildfly.clustering.tomcat.catalina.DistributableManager;
import org.wildfly.clustering.tomcat.catalina.IdentifierFactoryAdapter;
import org.wildfly.clustering.tomcat.catalina.LocalSessionContext;
import org.wildfly.clustering.tomcat.catalina.LocalSessionContextFactory;
import org.wildfly.clustering.tomcat.catalina.TomcatManager;
import org.wildfly.clustering.tomcat.catalina.TomcatSessionExpirationListener;
import org.wildfly.clustering.web.IdentifierFactory;
import org.wildfly.clustering.web.LocalContextFactory;
import org.wildfly.clustering.web.hotrod.session.HotRodSessionManager;
import org.wildfly.clustering.web.hotrod.session.HotRodSessionManagerFactory;
import org.wildfly.clustering.web.hotrod.session.HotRodSessionManagerFactoryConfiguration;
import org.wildfly.clustering.web.session.ImmutableSession;
import org.wildfly.clustering.web.session.SessionExpirationListener;
import org.wildfly.clustering.web.session.SessionManager;
import org.wildfly.clustering.web.session.SessionManagerConfiguration;
import org.wildfly.clustering.web.session.SessionManagerFactory;
import org.wildfly.clustering.web.session.SessionManagerFactoryConfiguration;
import org.wildfly.clustering.web.session.SessionManagerFactoryConfiguration.SessionAttributePersistenceStrategy;

/**
 * Distributed Manager implementation that configures a HotRod client.
 * @author Paul Ferraro
 */
public class HotRodManager extends ManagerBase {

    enum MarshallingVersion implements Function<ClassLoader, MarshallingConfiguration> {
        VERSION_1() {
            @Override
            public MarshallingConfiguration apply(ClassLoader loader) {
                MarshallingConfiguration config = new MarshallingConfiguration();
                config.setClassResolver(new DefaultContextClassResolver(loader));
                config.setClassTable(new SimpleClassTable(Serializable.class, Externalizable.class));
                config.setObjectTable(new ExternalizerObjectTable(loader));
                return config;
            }
        },
        ;
        static final MarshallingVersion CURRENT = VERSION_1;
    }

    private final Properties properties = new Properties();

    private volatile RemoteCacheManager container;
    private volatile TomcatManager manager;
    private volatile SessionAttributePersistenceStrategy persistenceStrategy = SessionAttributePersistenceStrategy.COARSE;

    public void setProperty(String name, String value) {
        this.properties.setProperty("infinispan.client.hotrod." + name, value);
    }

    public void setPersistenceStrategy(SessionAttributePersistenceStrategy strategy) {
        this.persistenceStrategy = strategy;
    }

    public void setPersistenceStrategy(String strategy) {
        this.setPersistenceStrategy(SessionAttributePersistenceStrategy.valueOf(strategy));
    }

    @Override
    protected void startInternal() throws LifecycleException {
        super.startInternal();

        Configuration configuration = new ConfigurationBuilder()
                .withProperties(this.properties)
                .nearCache().mode(NearCacheMode.INVALIDATED).maxEntries(this.getMaxActiveSessions())
                .marshaller(new HotRodMarshaller(HotRodSessionManager.class.getClassLoader()))
                .build();

        RemoteCacheManager container = new RemoteCacheManager(configuration, false);
        this.container = container;
        this.container.start();

        Context context = this.getContext();
        ClassLoader loader = context.getLoader().getClassLoader();
        Host host = (Host) context.getParent();
        // Deployment name = host name + context path + version
        String deploymentName = host.getName() + context.getName();
        int maxActiveSessions = this.getMaxActiveSessions();
        SessionAttributePersistenceStrategy strategy = this.persistenceStrategy;
        MarshallingContext marshallingContext = new SimpleMarshallingContextFactory().createMarshallingContext(new SimpleMarshallingConfigurationRepository(MarshallingVersion.class, MarshallingVersion.CURRENT, loader), loader);
        MarshalledValueFactory<MarshallingContext> marshallingFactory = new SimpleMarshalledValueFactory(marshallingContext);

        SessionManagerFactoryConfiguration<MarshallingContext> sessionManagerFactoryConfig = new SessionManagerFactoryConfiguration<MarshallingContext>() {
            @Override
            public int getMaxActiveSessions() {
                return maxActiveSessions;
            }

            @Override
            public SessionAttributePersistenceStrategy getAttributePersistenceStrategy() {
                return strategy;
            }

            @Override
            public String getDeploymentName() {
                return deploymentName;
            }

            @Override
            public String getCacheName() {
                return null;
            }

            @Override
            public MarshalledValueFactory<MarshallingContext> getMarshalledValueFactory() {
                return marshallingFactory;
            }

            @Override
            public MarshallingContext getMarshallingContext() {
                return marshallingContext;
            }
        };

        HotRodSessionManagerFactoryConfiguration<MarshallingContext> hotrodSessionManagerFactoryConfig = new HotRodSessionManagerFactoryConfiguration<MarshallingContext>() {
            @Override
            public SessionManagerFactoryConfiguration<MarshallingContext> getSessionManagerFactoryConfiguration() {
                return sessionManagerFactoryConfig;
            }

            @Override
            public RemoteCacheManager getCacheContainer() {
                return container;
            }
        };

        SessionManagerFactory<Batch> sessionManagerFactory = new HotRodSessionManagerFactory<>(hotrodSessionManagerFactoryConfig);

        ServletContext servletContext = context.getServletContext();
        SessionExpirationListener expirationListener = new TomcatSessionExpirationListener(context);
        LocalContextFactory<LocalSessionContext> contextFactory = new LocalSessionContextFactory();
        IdentifierFactory<String> identifierFactory = new IdentifierFactoryAdapter(this.getSessionIdGenerator());

        SessionManagerConfiguration<LocalSessionContext> sessionManagerConfiguration = new SessionManagerConfiguration<LocalSessionContext>() {
            @Override
            public ServletContext getServletContext() {
                return servletContext;
            }

            @Override
            public IdentifierFactory<String> getIdentifierFactory() {
                return identifierFactory;
            }

            @Override
            public SessionExpirationListener getExpirationListener() {
                return expirationListener;
            }

            @Override
            public LocalContextFactory<LocalSessionContext> getLocalContextFactory() {
                return contextFactory;
            }

            @Override
            public Recordable<ImmutableSession> getInactiveSessionRecorder() {
                return null;
            }
        };
        SessionManager<LocalSessionContext, Batch> sessionManager = sessionManagerFactory.createSessionManager(sessionManagerConfiguration);

        this.manager = new DistributableManager(sessionManager, context, marshallingContext);
        this.manager.start();

        this.setState(LifecycleState.STARTING);
    }

    @Override
    protected void stopInternal() throws LifecycleException {
        this.setState(LifecycleState.STOPPING);

        this.manager.stop();
        this.container.stop();
    }

    @Override
    public Session createSession(String sessionId) {
        return this.manager.createSession(sessionId);
    }

    @Override
    public Session findSession(String id) throws IOException {
        return this.manager.findSession(id);
    }

    @Override
    public void changeSessionId(Session session) {
        this.manager.changeSessionId(session);
    }

    @Override
    public void changeSessionId(Session session, String newId) {
        this.manager.changeSessionId(session, newId);
    }

    @Override
    public boolean willAttributeDistribute(String name, Object value) {
        return this.manager.willAttributeDistribute(name, value);
    }

    @Override
    public void load() throws ClassNotFoundException, IOException {
        // Do nothing
    }

    @Override
    public void unload() throws IOException {
        // Do nothing
    }

    @Override
    public void backgroundProcess() {
        // Do nothing
    }

    @Override
    public void processExpires() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(Session session) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Session createEmptySession() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Session[] findSessions() {
        // This would be super-expensive!!!
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(Session session) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove(Session session, boolean update) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String listSessionIds() {
        // This would be super-expensive
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSessionAttribute(String sessionId, String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HashMap<String, String> getSession(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void expireSession(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getThisAccessedTimestamp(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getThisAccessedTime(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLastAccessedTimestamp(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getLastAccessedTime(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getCreationTime(String sessionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getCreationTimestamp(String sessionId) {
        throw new UnsupportedOperationException();
    }
}
