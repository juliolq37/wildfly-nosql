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

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.InjectionTargetFactory;

/**
 * @author <a href="mailto:gytis@redhat.com">Gytis Trikleris</a>
 */
class OrientProducerFactory<T> implements InjectionTargetFactory<T> {

    private final Class<T> beanClass;

    private final String profile;

    OrientProducerFactory(Class<T> beanClass, String profile) {
        this.beanClass = beanClass;
        this.profile = profile;
    }

    @Override
    public InjectionTarget<T> createInjectionTarget(Bean<T> bean) {
        return new OrientInjectionTarget<>(beanClass, profile);
    }

}
