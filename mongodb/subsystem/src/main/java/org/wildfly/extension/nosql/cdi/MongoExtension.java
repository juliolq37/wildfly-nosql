/*
 * *
 *  * Copyright 2017 Red Hat, Inc, and individual contributors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.wildfly.extension.nosql.cdi;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanAttributes;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.InjectionTargetFactory;

import org.jboss.as.server.CurrentServiceContainer;
import org.wildfly.extension.nosql.subsystem.mongodb.MongoSubsystemService;
import org.wildfly.nosql.common.ConnectionServiceAccess;
import org.wildfly.nosql.common.SubsystemService;
import org.wildfly.nosql.common.spi.NoSQLConnection;


/**
 * This CDI Extension registers a <code>Mongoclient</code>
 * defined by @Inject in application beans
 * Registration will be aborted if user defines her own <code>MongoClient</code> bean or producer
 *
 * @author Antoine Sabot-Durand
 * @author Scott Marlow
 */
public class MongoExtension implements Extension {

    private final Class mongoClientClass;
    private final Class mongoDatabaseClass;

    public MongoExtension(Class mongoClientClass, Class mongoDatabaseClass) {
        this.mongoClientClass = mongoClientClass;
        this.mongoDatabaseClass = mongoDatabaseClass;
    }

    private static final Logger log = Logger.getLogger(MongoExtension.class.getName());

    void registerNoSQLSourceBeans(@Observes AfterBeanDiscovery abd, BeanManager bm) {
        if (bm.getBeans(mongoClientClass, DefaultLiteral.INSTANCE).isEmpty()) {
            // Iterate profiles and create Cluster/Session bean for each profile, that application code can @Inject
            for(String profile: getService().profileNames()) {
                log.log(Level.INFO, "Registering bean for profile {0}", profile);
                abd.addBean(bm.createBean(
                        new MongoClientBeanAttributes(bm.createBeanAttributes(bm.createAnnotatedType(mongoClientClass)), profile),
                        mongoClientClass, new MongoClientProducerFactory(profile, mongoClientClass)));
                abd.addBean(bm.createBean(
                        new MongoDatabaseBeanAttributes(bm.createBeanAttributes(bm.createAnnotatedType(mongoDatabaseClass)), profile),
                        mongoDatabaseClass, new MongoDatabaseProducerFactory(profile, mongoDatabaseClass)));
            }
         } else {
            log.log(Level.INFO, "Application contains a default MongoClient Bean, automatic registration will be disabled");
        }
    }

    private SubsystemService getService() {
        return (SubsystemService) CurrentServiceContainer.getServiceContainer().getService(MongoSubsystemService.serviceName()).getValue();
    }

    private static class MongoClientBeanAttributes<T> implements BeanAttributes<T> {

        private BeanAttributes<T> delegate;
        private final String profile;

        MongoClientBeanAttributes(BeanAttributes<T> beanAttributes, String profile) {
            delegate = beanAttributes;
            this.profile = profile;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Set<Annotation> getQualifiers() {
            Set<Annotation> qualifiers = new HashSet<>(delegate.getQualifiers());
            NamedLiteral namedLiteral = new NamedLiteral(profile);  // name the bean for @Inject @Named lookup
            qualifiers.add(namedLiteral);
            return qualifiers;
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return ApplicationScoped.class;
        }

        @Override
        public Set<Class<? extends Annotation>> getStereotypes() {
            return delegate.getStereotypes();
        }

        @Override
        public Set<Type> getTypes() {
            return delegate.getTypes();
        }

        @Override
        public boolean isAlternative() {
            return delegate.isAlternative();
        }
    }

    private static class MongoClientProducerFactory<T>
            implements InjectionTargetFactory<T> {
        final String profile;
        final Class mongoClientClass;

        MongoClientProducerFactory(String profile, Class mongoClientClass) {
            this.profile = profile;
            this.mongoClientClass = mongoClientClass;
        }

        @Override
        public InjectionTarget<T> createInjectionTarget(Bean<T> bean) {
            return new InjectionTarget<T>() {
                @Override
                public void inject(T instance, CreationalContext<T> ctx) {
                }

                @Override
                public void postConstruct(T instance) {
                }

                @Override
                public void preDestroy(T instance) {
                }

                @Override
                public T produce(CreationalContext<T> ctx) {
                    NoSQLConnection noSQLConnection = ConnectionServiceAccess.connection(profile);
                    return (T)noSQLConnection.unwrap(mongoClientClass);
                }

                @Override
                public void dispose(T connection) {
                    // connection.close();
                }

                @Override
                public Set<InjectionPoint> getInjectionPoints() {
                    return Collections.EMPTY_SET;
                }
            };
        }
    }

    private static class MongoDatabaseBeanAttributes<T> implements BeanAttributes<T> {

        private BeanAttributes<T> delegate;
        private final String profile;

        MongoDatabaseBeanAttributes(BeanAttributes<T> beanAttributes, String profile) {
            delegate = beanAttributes;
            this.profile = profile;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Set<Annotation> getQualifiers() {
            Set<Annotation> qualifiers = new HashSet<>(delegate.getQualifiers());
            NamedLiteral namedLiteral = new NamedLiteral(profile);  // name the bean for @Inject @Named lookup
            qualifiers.add(namedLiteral);
            return qualifiers;
        }

        @Override
        public Class<? extends Annotation> getScope() {
            return ApplicationScoped.class;
        }

        @Override
        public Set<Class<? extends Annotation>> getStereotypes() {
            return delegate.getStereotypes();
        }

        @Override
        public Set<Type> getTypes() {
            return delegate.getTypes();
        }

        @Override
        public boolean isAlternative() {
            return delegate.isAlternative();
        }
    }

    private static class MongoDatabaseProducerFactory<T>
            implements InjectionTargetFactory<T> {
        private final String profile;
        private final Class mongoDatabaseClass;

        MongoDatabaseProducerFactory(String profile, Class mongoDatabaseClass) {
            this.profile = profile;
            this.mongoDatabaseClass = mongoDatabaseClass;
        }

        @Override
        public InjectionTarget<T> createInjectionTarget(Bean<T> bean) {
            return new InjectionTarget<T>() {
                @Override
                public void inject(T instance, CreationalContext<T> ctx) {
                }

                @Override
                public void postConstruct(T instance) {
                }

                @Override
                public void preDestroy(T instance) {
                }

                @Override
                public T produce(CreationalContext<T> ctx) {
                    NoSQLConnection noSQLConnection = ConnectionServiceAccess.connection(profile);
                    return (T)noSQLConnection.unwrap(mongoDatabaseClass);
                }

                @Override
                public void dispose(T database) {

                }

                @Override
                public Set<InjectionPoint> getInjectionPoints() {
                    return Collections.EMPTY_SET;
                }
            };
        }
    }

}
