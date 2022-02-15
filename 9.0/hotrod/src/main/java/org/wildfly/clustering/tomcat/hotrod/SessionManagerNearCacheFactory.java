/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022, Red Hat, Inc., and individual contributors
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

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.infinispan.client.hotrod.MetadataValue;
import org.infinispan.client.hotrod.configuration.NearCacheConfiguration;
import org.infinispan.client.hotrod.near.NearCache;
import org.infinispan.client.hotrod.near.NearCacheFactory;
import org.wildfly.clustering.infinispan.client.near.CaffeineNearCache;
import org.wildfly.clustering.infinispan.client.near.SimpleKeyWeigher;
import org.wildfly.clustering.web.hotrod.session.SessionAccessMetaDataKey;
import org.wildfly.clustering.web.hotrod.session.SessionCreationMetaDataKey;
import org.wildfly.clustering.web.hotrod.session.coarse.SessionAttributesKey;
import org.wildfly.clustering.web.hotrod.session.fine.SessionAttributeKey;
import org.wildfly.clustering.web.hotrod.session.fine.SessionAttributeNamesKey;
import org.wildfly.clustering.web.session.SessionAttributePersistenceStrategy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;

/**
 * Custom near cache factory, based on max active sessions.
 * @author Paul Ferraro
 */
public class SessionManagerNearCacheFactory implements NearCacheFactory {

    private final Integer maxActiveSessions;
    private final SessionAttributePersistenceStrategy strategy;

    public SessionManagerNearCacheFactory(Integer maxActiveSessions, SessionAttributePersistenceStrategy strategy) {
        this.maxActiveSessions = maxActiveSessions;
        this.strategy = strategy;
    }

    @Override
    public <K, V> NearCache<K, V> createNearCache(NearCacheConfiguration config) {
        AtomicReference<Cache<K, MetadataValue<V>>> reference = new AtomicReference<>();
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (this.maxActiveSessions != null) {
            builder.executor(Runnable::run)
                    .maximumWeight(this.maxActiveSessions.longValue())
                    .weigher(new SimpleKeyWeigher(SessionCreationMetaDataKey.class::isInstance))
                    .removalListener(new CascadeRemovalListener<>(this.strategy, reference));
        }
        Cache<K, MetadataValue<V>> cache = builder.build();
        // Set reference for use by removal listener
        reference.set(cache);
        return new CaffeineNearCache<>(cache);
    }

    private static class CascadeRemovalListener<K, V> implements RemovalListener<Object, Object> {
        private final AtomicReference<Cache<K, MetadataValue<V>>> reference;
        private final SessionAttributePersistenceStrategy strategy;

        CascadeRemovalListener(SessionAttributePersistenceStrategy strategy, AtomicReference<Cache<K, MetadataValue<V>>> reference) {
            this.strategy = strategy;
            this.reference = reference;
        }

        @Override
        public void onRemoval(Object key, Object value, RemovalCause cause) {
            // Cascade invalidation to dependent entries
            if ((cause == RemovalCause.SIZE) && (key instanceof SessionCreationMetaDataKey)) {
                String id = ((SessionCreationMetaDataKey) key).getId();
                Cache<K, MetadataValue<V>> cache = this.reference.get();
                List<Object> keys = new LinkedList<>();
                keys.add(new SessionAccessMetaDataKey(id));
                switch (this.strategy) {
                    case COARSE: {
                        keys.add(new SessionAttributesKey(id));
                        break;
                    }
                    case FINE: {
                        SessionAttributeNamesKey namesKey = new SessionAttributeNamesKey(id);
                        keys.add(namesKey);
                        MetadataValue<V> namesValue = cache.getIfPresent(namesKey);
                        if (namesValue != null) {
                            @SuppressWarnings("unchecked")
                            Map<String, UUID> names = (Map<String, UUID>) namesValue.getValue();
                            for (UUID attributeId : names.values()) {
                                keys.add(new SessionAttributeKey(id, attributeId));
                            }
                        }
                        break;
                    }
                }
                cache.invalidateAll(keys);
            }
        }
    }
}
