/*
 * Copyright 2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.nosql.common;

import static org.wildfly.nosql.common.NoSQLLogger.ROOT_LOGGER;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.enterprise.inject.spi.Extension;
import javax.inject.Named;

import org.jboss.as.ee.weld.WeldDeploymentMarker;
import org.jboss.as.server.CurrentServiceContainer;
import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.annotation.CompositeIndex;
import org.jboss.as.weld.deployment.WeldPortableExtensions;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.MethodInfo;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.msc.service.ServiceName;

/**
 * DriverScanDependencyProcessor
 *
 * @author Scott Marlow
 */
public class DriverScanDependencyProcessor implements DeploymentUnitProcessor {

    private static final DotName RESOURCE_ANNOTATION_NAME = DotName.createSimple(Resource.class.getName());
    private static final DotName RESOURCES_ANNOTATION_NAME = DotName.createSimple(Resources.class.getName());
    private static final DotName NAMED_ANNOTATION_NAME = DotName.createSimple(Named.class.getName());
    // no more than one NoSQL (per backend database type) driver module can be used by an deployment.
    // For example, you cannot include two different/separate MongoDB driver modules in a deployment.
    private static final AttachmentKey<Map<String, String>> perModuleNameKey = AttachmentKey.create(Map.class);

    private ServiceName serviceName;

    public DriverScanDependencyProcessor(String serviceName) {
        this.serviceName = ServiceName.JBOSS.append(serviceName);
    }

    /**
     * Add dependencies for modules required for NoSQL deployments
     */
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final CompositeIndex index = deploymentUnit.getAttachment(Attachments.COMPOSITE_ANNOTATION_INDEX);

        // handle @Resource
        final List<AnnotationInstance> resourceAnnotations = index.getAnnotations(RESOURCE_ANNOTATION_NAME);
        for (AnnotationInstance annotation : resourceAnnotations) {
            final AnnotationTarget annotationTarget = annotation.target();
            final AnnotationValue lookupValue = annotation.value("lookup");
            final String lookup = lookupValue != null ? lookupValue.asString() : null;
            if (lookup != null) {
                if (annotationTarget instanceof FieldInfo) {
                    processFieldResource(deploymentUnit, lookup);
                } else if (annotationTarget instanceof MethodInfo) {
                    final MethodInfo methodInfo = (MethodInfo) annotationTarget;
                    processMethodResource(deploymentUnit, methodInfo, lookup);
                } else if (annotationTarget instanceof ClassInfo) {
                    processClassResource(deploymentUnit, lookup);
                }
            }
        }

        // handle @Resources
        final List<AnnotationInstance> resourcesAnnotations = index.getAnnotations(RESOURCES_ANNOTATION_NAME);
        for (AnnotationInstance outerAnnotation : resourcesAnnotations) {
            final AnnotationTarget annotationTarget = outerAnnotation.target();
            if (annotationTarget instanceof ClassInfo) {
                final AnnotationInstance[] values = outerAnnotation.value("value").asNestedArray();
                for (AnnotationInstance annotation : values) {
                    final AnnotationValue lookupValue = annotation.value("lookup");
                    final String lookup = lookupValue != null ? lookupValue.asString() : null;
                    if (lookup != null) {
                        processClassResource(deploymentUnit, lookup);
                    }
                }
            }
        }

        // handle CDI @Named for @Inject, look for any @Named value that matches a NoSQL profile name
        final List<AnnotationInstance> namedAnnotations = index.getAnnotations(NAMED_ANNOTATION_NAME);
        for (AnnotationInstance annotation : namedAnnotations) {
            final AnnotationTarget annotationTarget = annotation.target();
            final AnnotationValue profileValue = annotation.value("value");
            final String profile = profileValue != null ? profileValue.asString() : null;

            if (annotationTarget instanceof FieldInfo) {
                processFieldNamedQualifier(deploymentUnit, profile);
            } else if (annotationTarget instanceof MethodInfo) {
                final MethodInfo methodInfo = (MethodInfo) annotationTarget;
                processMethodNamedQualifier(deploymentUnit, methodInfo, profile);
            } else if (annotationTarget instanceof ClassInfo) {
                processClassNamedQualifier(deploymentUnit, profile);
            }
        }
    }

    private void processClassNamedQualifier(DeploymentUnit deploymentUnit, String profile) {
        if (isEmpty(profile)) {
            ROOT_LOGGER.annotationAttributeMissing("@Named", "value");
            return;
        }
        SubsystemService service = getService();
        String moduleName = service.moduleNameFromProfile(profile);
        if (moduleName != null) {
            savePerDeploymentModuleName(deploymentUnit, moduleName, service.vendorKey());
            ROOT_LOGGER.scannedNamedQualifier(profile, moduleName);
        } else {
            ROOT_LOGGER.ignoringNamedQualifier(profile,getService().profileNames());
        }


    }

    private void processMethodNamedQualifier(DeploymentUnit deploymentUnit, MethodInfo methodInfo, String profile) {

        SubsystemService service = getService();
        String moduleName = getService().moduleNameFromProfile(profile);
        if (moduleName != null) {
            savePerDeploymentModuleName(deploymentUnit, moduleName, service.vendorKey());
            ROOT_LOGGER.scannedNamedQualifier(profile, moduleName);
        } else {
            ROOT_LOGGER.ignoringNamedQualifier(profile,getService().profileNames());
        }

    }

    private void processFieldNamedQualifier(DeploymentUnit deploymentUnit, String profile) {
        SubsystemService service = getService();
        String moduleName = getService().moduleNameFromProfile(profile);
        if (moduleName != null) {
            savePerDeploymentModuleName(deploymentUnit, moduleName, service.vendorKey());
            ROOT_LOGGER.scannedNamedQualifier(profile, moduleName);
        } else {
            ROOT_LOGGER.ignoringNamedQualifier(profile,getService().profileNames());
        }
    }

    protected void processFieldResource(final DeploymentUnit deploymentUnit, String lookup) throws DeploymentUnitProcessingException {
        SubsystemService service = getService();
        String moduleName = getService().moduleNameFromJndi(lookup);
        if (moduleName != null) {
            savePerDeploymentModuleName(deploymentUnit, moduleName, service.vendorKey());
            ROOT_LOGGER.scannedResourceLookup(lookup, moduleName);
        } else {
            ROOT_LOGGER.ignoringResourceLookup(lookup, getService().jndiNames());
        }

    }

    protected void processMethodResource(final DeploymentUnit deploymentUnit, final MethodInfo methodInfo, final String lookup) throws DeploymentUnitProcessingException {
        SubsystemService service = getService();
        String moduleName = getService().moduleNameFromJndi(lookup);
        if (moduleName != null) {
            savePerDeploymentModuleName(deploymentUnit, moduleName, service.vendorKey());
            ROOT_LOGGER.scannedResourceLookup(lookup, moduleName);
        } else {
            ROOT_LOGGER.ignoringResourceLookup(lookup, getService().jndiNames());
        }
    }

    protected void processClassResource(final DeploymentUnit deploymentUnit, final String lookup) throws DeploymentUnitProcessingException {
        if (isEmpty(lookup)) {
            ROOT_LOGGER.annotationAttributeMissing("@Resource", "lookup");
            return;
        }

        SubsystemService service = getService();
        String moduleName = getService().moduleNameFromJndi(lookup);
        if (moduleName != null) {
            savePerDeploymentModuleName(deploymentUnit, moduleName, service.vendorKey());
            ROOT_LOGGER.scannedResourceLookup(lookup, moduleName);
        } else {
            ROOT_LOGGER.ignoringResourceLookup(lookup, getService().jndiNames());
        }
    }

    private void savePerDeploymentModuleName(DeploymentUnit deploymentUnit, String module, String vendorKey) {
        if (deploymentUnit.getParent() != null) {
            deploymentUnit = deploymentUnit.getParent();
        }
        synchronized (deploymentUnit) {
            Map currentValue = deploymentUnit.getAttachment(perModuleNameKey);
            // setup up Map<vendorKey, driver module name) that ensures that only one driver
            // module per vendor type, is used by deployments.
            // It is legal for deployments to use one MongoDB driver module and one Cassandra driver Module.
            // It is illegal for deployments to use two different MongoDB driver modules.
            if (currentValue == null) {
                currentValue = new HashMap();
                deploymentUnit.putAttachment(perModuleNameKey, currentValue);
            }
            // check if there are two different NoSQL driver modules being used for the same NoSQL backend vendor
            if (currentValue.get(vendorKey) != null && !currentValue.get(vendorKey).equals(module)) {
                // deployment is using two different MongoDB (or whichever database type) driver modules, fail deployment.
                throw ROOT_LOGGER.cannotAddReferenceToModule(module, currentValue.get(vendorKey), deploymentUnit.getName());
            }
            currentValue.put(vendorKey, module);
        }

        // register CDI extensions for each NoSQL driver that is used by deployment
        final ModuleLoader moduleLoader = Module.getBootModuleLoader();
        mongoSetup(deploymentUnit, moduleLoader, module);
        cassandraSetup(deploymentUnit, moduleLoader, module);
        neo4jSetup(deploymentUnit, moduleLoader, module);
        orientSetup(deploymentUnit, moduleLoader, module);
    }

    protected static Map<String,String> getPerDeploymentDeploymentModuleName(DeploymentUnit deploymentUnit) {
        if (deploymentUnit.getParent() != null) {
            deploymentUnit = deploymentUnit.getParent();
        }
        synchronized (deploymentUnit) {
            return deploymentUnit.getAttachment(perModuleNameKey);
        }
    }

    private void mongoSetup(DeploymentUnit deploymentUnit, ModuleLoader moduleLoader, String nosqlDriverModuleName) {
        Class mongoClientClass, mongoDatabaseClass;
        MethodHandleBuilder methodHandleBuilder = new MethodHandleBuilder();
        try {
            mongoClientClass = moduleLoader.loadModule(ModuleIdentifier.fromString(nosqlDriverModuleName)).getClassLoader().loadClass(NoSQLConstants.MONGOCLIENTCLASS);
            mongoDatabaseClass = moduleLoader.loadModule(ModuleIdentifier.fromString(nosqlDriverModuleName)).getClassLoader().loadClass(NoSQLConstants.MONGODATABASECLASS);

        } catch (ClassNotFoundException expected) {
            // ignore CNFE which just means that module is not a MongoDB module
            return;
        } catch (ModuleLoadException e) {
            throw new RuntimeException("could not load NoSQL driver module " + nosqlDriverModuleName, e);
        }
        // only reach this point if module is a MongoDB driver
        try {
            final DeploymentUnit parent = deploymentUnit.getParent() == null ? deploymentUnit : deploymentUnit.getParent();
            if (WeldDeploymentMarker.isPartOfWeldDeployment(deploymentUnit)) {
                WeldPortableExtensions extensions = WeldPortableExtensions.getPortableExtensions(parent);
                ModuleIdentifier mongoCDIExtensionModule = ModuleIdentifier.create(NoSQLConstants.MONGOCDIEXTENSIONMODULE);
                methodHandleBuilder.classLoader(mongoCDIExtensionModule);
                methodHandleBuilder.className(NoSQLConstants.MONGOCDIEXTENSIONCLASS);
                MethodHandle extensionCtor = methodHandleBuilder.constructor(MethodType.methodType(void.class, Class.class, Class.class));

                Extension extension = (Extension) extensionCtor.invoke(mongoClientClass, mongoDatabaseClass);
                extensions.registerExtensionInstance(extension, parent);
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("unexpected error constructing " + methodHandleBuilder.getTargetClass().getName(), throwable);
        }
    }

    private void orientSetup(DeploymentUnit deploymentUnit, ModuleLoader moduleLoader, String nosqlDriverModuleName) {
        Class oPartitionedDatabasePoolClass;
        MethodHandleBuilder methodHandleBuilder = new MethodHandleBuilder();
        try {
            oPartitionedDatabasePoolClass = moduleLoader.loadModule(ModuleIdentifier.fromString(nosqlDriverModuleName)).getClassLoader().loadClass(NoSQLConstants.ORIENTDBPARTIONEDDBPOOLCLASS);
        } catch (ClassNotFoundException expected) {
            // ignore CNFE which just means that module is not a OrientDB module
            return;
        } catch (ModuleLoadException e) {
            throw new RuntimeException("could not load NoSQL driver module " + nosqlDriverModuleName, e);
        }
        // only reach this point if module is a Orient driver
        try {
            final DeploymentUnit parent = deploymentUnit.getParent() == null ? deploymentUnit : deploymentUnit.getParent();
            if (WeldDeploymentMarker.isPartOfWeldDeployment(deploymentUnit)) {
                WeldPortableExtensions extensions = WeldPortableExtensions.getPortableExtensions(parent);
                ModuleIdentifier cdiExtensionModule = ModuleIdentifier.create(NoSQLConstants.ORIENTDBCDIEXTENSIONMODULE);
                methodHandleBuilder.classLoader(cdiExtensionModule);
                methodHandleBuilder.className(NoSQLConstants.ORIENTCDIEXTENSIONCLASS);
                MethodHandle extensionCtor = methodHandleBuilder.constructor(MethodType.methodType(void.class, Class.class));

                Extension extension = (Extension) extensionCtor.invoke(oPartitionedDatabasePoolClass);
                extensions.registerExtensionInstance(extension, parent);
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("unexpected error constructing " + methodHandleBuilder.getTargetClass().getName(), throwable);
        }
    }

    private void neo4jSetup(DeploymentUnit deploymentUnit, ModuleLoader moduleLoader, String nosqlDriverModuleName) {
        Class driverClass;
        MethodHandleBuilder methodHandleBuilder = new MethodHandleBuilder();
        try {
            driverClass = moduleLoader.loadModule(ModuleIdentifier.fromString(nosqlDriverModuleName)).getClassLoader().loadClass(NoSQLConstants.NEO4JDRIVERCLASS);
        } catch (ClassNotFoundException expected) {
            // ignore CNFE which just means that module is not a Neo4j module
            return;
        } catch (ModuleLoadException e) {
            throw new RuntimeException("could not load NoSQL driver module " + nosqlDriverModuleName, e);
        }
        // only reach this point if module is a Neo4j driver
        try {
            final DeploymentUnit parent = deploymentUnit.getParent() == null ? deploymentUnit : deploymentUnit.getParent();
            if (WeldDeploymentMarker.isPartOfWeldDeployment(deploymentUnit)) {
                WeldPortableExtensions extensions = WeldPortableExtensions.getPortableExtensions(parent);
                ModuleIdentifier cdiExtensionModule = ModuleIdentifier.create(NoSQLConstants.NEO4JCDIEXTENSIONMODULE);
                methodHandleBuilder.classLoader(cdiExtensionModule);
                methodHandleBuilder.className(NoSQLConstants.NEO4JCDIEXTENSIONCLASS);
                MethodHandle extensionCtor = methodHandleBuilder.constructor(MethodType.methodType(void.class, Class.class));

                Extension extension = (Extension) extensionCtor.invoke(driverClass);
                extensions.registerExtensionInstance(extension, parent);
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("unexpected error constructing " + methodHandleBuilder.getTargetClass().getName(), throwable);
        }
    }

    private void cassandraSetup(DeploymentUnit deploymentUnit, ModuleLoader moduleLoader, String nosqlDriverModuleName) {
        Class clusterClass;
        Class sessionClass;

        MethodHandleBuilder methodHandleBuilder = new MethodHandleBuilder();
        try {
            clusterClass = moduleLoader.loadModule(ModuleIdentifier.fromString(nosqlDriverModuleName)).getClassLoader().loadClass(NoSQLConstants.CASSANDRACLUSTERCLASS);
            sessionClass = moduleLoader.loadModule(ModuleIdentifier.fromString(nosqlDriverModuleName)).getClassLoader().loadClass(NoSQLConstants.CASSANDRASESSIONCLASS);
        } catch (ClassNotFoundException expected) {
            // ignore CNFE which just means that module is not a Cassandra module
            return;
        } catch (ModuleLoadException e) {
            throw new RuntimeException("could not load NoSQL driver module " + nosqlDriverModuleName, e);
        }
        // only reach this point if module is a Cassandra driver
        try {
            final DeploymentUnit parent = deploymentUnit.getParent() == null ? deploymentUnit : deploymentUnit.getParent();
            if (WeldDeploymentMarker.isPartOfWeldDeployment(deploymentUnit)) {
                WeldPortableExtensions extensions = WeldPortableExtensions.getPortableExtensions(parent);
                ModuleIdentifier cdiExtensionModule = ModuleIdentifier.create(NoSQLConstants.CASSANDRACDIEXTENSIONMODULE);
                methodHandleBuilder.classLoader(cdiExtensionModule);
                methodHandleBuilder.className(NoSQLConstants.CASSANDRACDIEXTENSIONCLASS);
                MethodHandle extensionCtor = methodHandleBuilder.constructor(MethodType.methodType(void.class, Class.class, Class.class));

                Extension extension = (Extension) extensionCtor.invoke(clusterClass, sessionClass);
                extensions.registerExtensionInstance(extension, parent);
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("unexpected error constructing " + methodHandleBuilder.getTargetClass().getName(), throwable);
        }

    }

    private SubsystemService getService() {
        return (SubsystemService) CurrentServiceContainer.getServiceContainer().getService(serviceName).getValue();
    }


    private boolean isEmpty(final String string) {
        return string == null || string.isEmpty();
    }

    @Override
    public void undeploy(DeploymentUnit context) {

    }
}
