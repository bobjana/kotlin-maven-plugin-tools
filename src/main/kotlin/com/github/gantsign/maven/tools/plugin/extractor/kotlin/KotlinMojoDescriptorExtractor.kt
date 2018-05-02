/*-
 * #%L
 * kotlin-maven-plugin-tools
 * %%
 * Copyright (C) 2018 GantSign Ltd.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.gantsign.maven.tools.plugin.extractor.kotlin

import com.github.gantsign.maven.tools.plugin.extractor.kotlin.internal.AnnotationScanner
import com.github.gantsign.maven.tools.plugin.extractor.kotlin.internal.SourceScanner
import com.thoughtworks.qdox.model.DocletTag
import com.thoughtworks.qdox.model.JavaClass
import com.thoughtworks.qdox.model.JavaField
import org.apache.maven.plugin.descriptor.InvalidParameterException
import org.apache.maven.plugin.descriptor.MojoDescriptor
import org.apache.maven.plugin.descriptor.Parameter
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugin.descriptor.Requirement
import org.apache.maven.repository.RepositorySystem
import org.apache.maven.tools.plugin.ExtendedMojoDescriptor
import org.apache.maven.tools.plugin.PluginToolsRequest
import org.apache.maven.tools.plugin.extractor.MojoDescriptorExtractor
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.ComponentAnnotationContent
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.ExecuteAnnotationContent
import org.apache.maven.tools.plugin.extractor.annotations.datamodel.ParameterAnnotationContent
import org.apache.maven.tools.plugin.extractor.annotations.scanner.MojoAnnotatedClass
import org.apache.maven.tools.plugin.extractor.annotations.scanner.MojoAnnotationsScanner
import org.apache.maven.tools.plugin.util.PluginUtils
import org.codehaus.plexus.archiver.manager.ArchiverManager
import org.codehaus.plexus.component.annotations.Component
import org.codehaus.plexus.logging.AbstractLogEnabled
import org.codehaus.plexus.component.annotations.Requirement as PlexusRequirement

/**
 * KotlinDescriptorExtractor, a MojoDescriptor extractor to read descriptors from Kotlin.
 */
@Component(role = MojoDescriptorExtractor::class, hint = "kotlin")
class JavaAnnotationsMojoDescriptorExtractor : AbstractLogEnabled(), MojoDescriptorExtractor {

    @PlexusRequirement
    private lateinit var mojoAnnotationsScanner: MojoAnnotationsScanner

    @PlexusRequirement
    private lateinit var repositorySystem: RepositorySystem

    @PlexusRequirement
    private lateinit var archiverManager: ArchiverManager

    override fun execute(request: PluginToolsRequest): List<MojoDescriptor> {

        val mojoAnnotatedClasses =
            AnnotationScanner(mojoAnnotationsScanner).scanAnnotations(request)

        val javaClassesMap =
            SourceScanner(logger, repositorySystem, archiverManager)
                .scanJavadoc(request, mojoAnnotatedClasses.values)

        mojoAnnotatedClasses.populateDataFromJavadoc(javaClassesMap)

        return mojoAnnotatedClasses.toMojoDescriptors(request.pluginDescriptor)
    }

    /**
     * Scan sources to get @since and @deprecated and description of classes and fields.
     */
    private fun Map<String, MojoAnnotatedClass>.populateDataFromJavadoc(
        javaClassesMap: Map<String, JavaClass>
    ) {
        val mojoAnnotatedClasses = this

        for ((className, mojoAnnotatedClass) in mojoAnnotatedClasses) {
            val javaClass = javaClassesMap[className] ?: continue

            // populate class-level content
            mojoAnnotatedClass.mojo?.also { mojoAnnotationContent ->
                mojoAnnotationContent.description = javaClass.comment

                javaClass.findInClassHierarchy("since")
                    ?.let { mojoAnnotationContent.since = it.value }

                javaClass.findInClassHierarchy("deprecated")
                    ?.let { mojoAnnotationContent.deprecated = it.value }
            }

            val fieldsMap = javaClass.extractFieldParameterTags(javaClassesMap)

            // populate parameters
            val parameters: Map<String, ParameterAnnotationContent> =
                mojoAnnotatedClass.gatherParametersFromClassHierarchy(mojoAnnotatedClasses)
                    .toSortedMap()

            for ((fieldName, parameterAnnotationContent) in parameters) {
                val javaField = fieldsMap[fieldName] ?: continue

                parameterAnnotationContent.description = javaField.comment

                javaField.getTagByName("deprecated")
                    ?.let { parameterAnnotationContent.deprecated = it.value }

                javaField.getTagByName("since")
                    ?.let { parameterAnnotationContent.since = it.value }
            }

            // populate components
            val components: Map<String, ComponentAnnotationContent> =
                mojoAnnotatedClass.components!!

            for ((fieldName, componentAnnotationContent) in components) {
                val javaField = fieldsMap[fieldName] ?: continue

                componentAnnotationContent.description = javaField.comment

                javaField.getTagByName("deprecated")
                    ?.let { componentAnnotationContent.deprecated = it.value }

                javaField.getTagByName("since")
                    ?.let { componentAnnotationContent.since = it.value }
            }
        }
    }

    private tailrec fun JavaClass.findInClassHierarchy(tagName: String): DocletTag? {
        val tag: DocletTag? = getTagByName(tagName)

        if (tag != null) return tag

        val superClass: JavaClass = superJavaClass ?: return null

        return superClass.findInClassHierarchy(tagName)
    }

    /**
     * Extract fields that are either parameters or components.
     *
     * @return map with Mojo parameters names as keys.
     */
    private tailrec fun JavaClass.extractFieldParameterTags(
        javaClassesMap: Map<String, JavaClass>,
        descendantParams: Map<String, JavaField> = mapOf()
    ): MutableMap<String, JavaField> {

        val superClass: JavaClass? = superJavaClass

        val searchSuperClass = when {
            superClass == null -> null
            superClass.fields.isNotEmpty() -> superClass
            else -> {
                // maybe sources comes from scan of sources artifact
                javaClassesMap[superClass.fullyQualifiedName]
            }
        }

        val localParams: Map<String, JavaField> = fields.associateBy { it.name }

        // the descendant params must overwrite local (parent) params
        val mergedParams = localParams.toSortedMap().also { it.putAll(descendantParams) }
        if (searchSuperClass == null) {
            return mergedParams
        }
        return searchSuperClass.extractFieldParameterTags(javaClassesMap, mergedParams)
    }

    private fun Map<String, MojoAnnotatedClass>.toMojoDescriptors(
        pluginDescriptor: PluginDescriptor
    ): List<MojoDescriptor> {
        val mojoAnnotatedClasses = this

        return mojoAnnotatedClasses.values
            .filter { mojoAnnotatedClass ->
                // no mojo so skip it
                mojoAnnotatedClass.mojo != null
            }
            .map { mojoAnnotatedClass ->
                mojoAnnotatedClass.toMojoDescriptor(mojoAnnotatedClasses, pluginDescriptor)
            }
    }

    private fun MojoAnnotatedClass.toMojoDescriptor(
        mojoAnnotatedClasses: Map<String, MojoAnnotatedClass>,
        pluginDescriptor: PluginDescriptor
    ): MojoDescriptor {
        val mojoAnnotatedClass = this

        return ExtendedMojoDescriptor().apply {

            implementation = mojoAnnotatedClass.className
            language = "java"

            val mojo = mojoAnnotatedClass.mojo!!

            description = mojo.description
            since = mojo.since
            mojo.deprecated = mojo.deprecated

            isProjectRequired = mojo.requiresProject()

            isRequiresReports = mojo.requiresReports()

            componentConfigurator = mojo.configurator()

            isInheritedByDefault = mojo.inheritByDefault()

            instantiationStrategy = mojo.instantiationStrategy().id()

            isAggregator = mojo.aggregator()
            isDependencyResolutionRequired = mojo.requiresDependencyResolution().id()
            dependencyCollectionRequired = mojo.requiresDependencyCollection().id()

            isDirectInvocationOnly = mojo.requiresDirectInvocation()
            deprecated = mojo.deprecated
            isThreadSafe = mojo.threadSafe()


            mojoAnnotatedClass.findExecuteInClassHierarchy(mojoAnnotatedClasses)?.also { execute ->
                executeGoal = execute.goal()
                executeLifecycle = execute.lifecycle()
                execute.phase()?.also { executePhase = it.id() }
            }

            executionStrategy = mojo.executionStrategy()

            goal = mojo.name()
            isOnlineRequired = mojo.requiresOnline()

            phase = mojo.defaultPhase().id()

            // Parameter annotations
            mojoAnnotatedClass.gatherParametersFromClassHierarchy(mojoAnnotatedClasses)
                .values
                .toSortedSet()
                .forEach { parameterAnnotationContent ->
                    addParameter(parameterAnnotationContent.toParameter())
                }

            // Component annotations
            mojoAnnotatedClass.gatherComponentsFromClassHierarchy(mojoAnnotatedClasses)
                .values
                .toSortedSet()
                .forEach { componentAnnotationContent ->
                    addParameter(componentAnnotationContent.toParameter(mojoAnnotatedClass))
                }

            this.pluginDescriptor = pluginDescriptor
        }
    }

    private fun ParameterAnnotationContent.toParameter(): Parameter {
        val parameterAnnotationContent = this

        return Parameter().apply {
            name = parameterAnnotationContent.name()
                ?.takeUnless(String::isEmpty)
                ?: parameterAnnotationContent.fieldName
            alias = parameterAnnotationContent.alias()
            defaultValue = parameterAnnotationContent.defaultValue()
            deprecated = parameterAnnotationContent.deprecated
            description = parameterAnnotationContent.description
            isEditable = !parameterAnnotationContent.readonly()
            val property: String? = parameterAnnotationContent.property()
            if (property?.let { '$' in it || '{' in it || '}' in it } == true) {
                throw InvalidParameterException(
                    "Invalid property for parameter '$name', forbidden characters '\${}': $property",
                    null
                )
            }
            expression = property?.let { "\${$it}" } ?: ""
            type = parameterAnnotationContent.className
            since = parameterAnnotationContent.since
            isRequired = parameterAnnotationContent.required()
        }
    }

    private fun ComponentAnnotationContent.toParameter(mojoAnnotatedClass: MojoAnnotatedClass): Parameter {
        val componentAnnotationContent = this

        return Parameter().apply {
            name = componentAnnotationContent.fieldName

            // recognize Maven-injected objects as components annotations instead of parameters
            @Suppress("DEPRECATION")
            val expression = PluginUtils.MAVEN_COMPONENTS[componentAnnotationContent.roleClassName]

            if (expression == null) {
                // normal component
                requirement = Requirement(
                    componentAnnotationContent.roleClassName,
                    componentAnnotationContent.hint()
                )
            } else {
                // not a component but a Maven object to be transformed into an expression/property: deprecated
                logger.warn("Deprecated @Component annotation for '$name' field in ${mojoAnnotatedClass.className}: replace with @Parameter( defaultValue = \"$expression\", readonly = true )")
                defaultValue = expression
                type = componentAnnotationContent.roleClassName
                isRequired = true
            }
            deprecated = componentAnnotationContent.deprecated
            since = componentAnnotationContent.since
            isEditable = false
        }
    }

    private tailrec fun MojoAnnotatedClass.findExecuteInClassHierarchy(
        mojoAnnotatedClasses: Map<String, MojoAnnotatedClass>
    ): ExecuteAnnotationContent? {
        val mojoAnnotatedClass = this

        if (mojoAnnotatedClass.execute != null) {
            return mojoAnnotatedClass.execute
        }

        val parentClassName: String = mojoAnnotatedClass.parentClassName
            ?.takeUnless(String::isEmpty)
            ?: return null

        val parent = mojoAnnotatedClasses[parentClassName] ?: return null
        return parent.findExecuteInClassHierarchy(mojoAnnotatedClasses)
    }

    private tailrec fun MojoAnnotatedClass.gatherParametersFromClassHierarchy(
        mojoAnnotatedClasses: Map<String, MojoAnnotatedClass>,
        descendantParameterAnnotationContents: Map<String, ParameterAnnotationContent> = mapOf()
    ): Map<String, ParameterAnnotationContent> {
        val mojoAnnotatedClass = this

        val localParameterAnnotationContents =
            mojoAnnotatedClass.parameters.values.associateBy { it.fieldName!! }

        val mergedParameterAnnotationContents =
            localParameterAnnotationContents + descendantParameterAnnotationContents

        val parentClassName: String =
            mojoAnnotatedClass.parentClassName ?: return mergedParameterAnnotationContents

        val parent =
            mojoAnnotatedClasses[parentClassName] ?: return mergedParameterAnnotationContents

        return parent.gatherParametersFromClassHierarchy(
            mojoAnnotatedClasses,
            mergedParameterAnnotationContents
        )
    }

    private tailrec fun MojoAnnotatedClass.gatherComponentsFromClassHierarchy(
        mojoAnnotatedClasses: Map<String, MojoAnnotatedClass>,
        descendantComponentAnnotationContents: Map<String, ComponentAnnotationContent> = mapOf()
    ): Map<String, ComponentAnnotationContent> {
        val mojoAnnotatedClass = this

        val localComponentAnnotationContent =
            mojoAnnotatedClass.components.values.associateBy { it.fieldName!! }

        val mergedComponentAnnotationContents =
            localComponentAnnotationContent + descendantComponentAnnotationContents

        val parentClassName: String =
            mojoAnnotatedClass.parentClassName ?: return mergedComponentAnnotationContents

        val parent =
            mojoAnnotatedClasses[parentClassName] ?: return mergedComponentAnnotationContents

        return parent.gatherComponentsFromClassHierarchy(
            mojoAnnotatedClasses,
            mergedComponentAnnotationContents
        )
    }
}