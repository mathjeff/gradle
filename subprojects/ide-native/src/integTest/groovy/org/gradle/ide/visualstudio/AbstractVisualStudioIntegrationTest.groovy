/*
 * Copyright 2018 the original author or authors.
 *
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
 */

package org.gradle.ide.visualstudio

import org.gradle.ide.visualstudio.fixtures.AbstractVisualStudioIntegrationSpec
import org.gradle.ide.visualstudio.fixtures.ProjectFile
import org.gradle.ide.visualstudio.fixtures.SolutionFile
import org.gradle.language.cpp.CppTaskNames
import org.gradle.nativeplatform.fixtures.app.CppSourceElement

abstract class AbstractVisualStudioIntegrationTest extends AbstractVisualStudioIntegrationSpec implements CppTaskNames {

    def setup() {
        buildFile << """
            allprojects {
                apply plugin: 'visual-studio'
            }
        """
        settingsFile << """
            rootProject.name = "${rootProjectName}"
        """
    }

    def "ignores target machine not buildable from project configuration dimensions"() {
        when:
        componentUnderTest.writeToProject(testDirectory)
        makeSingleProject()
        buildFile << """
            ${componentUnderTestDsl}.targetMachines = [machines.os('os-family'), machines.${currentOsFamilyName}().x86(), machines.${currentOsFamilyName}().x86_64()]
        """

        and:
        run "visualStudio"

        then:
        result.assertTasksExecuted(":visualStudio", ":appVisualStudioSolution", projectTasks)

        and:
        def contexts = VariantContext.of(VariantDimension.of("buildType", ['debug', 'release']), VariantDimension.of("architecture", ['x86', 'x86-64']))
        def projectConfigurations = contexts*.asVariantName as Set
        projectFile.projectConfigurations.keySet() == projectConfigurations

        and:
        solutionFile.assertReferencesProject(projectFile, projectConfigurations)
    }

    def "create visual studio solution for component with multiple target machines"() {
        when:
        componentUnderTest.writeToProject(testDirectory)
        makeSingleProject()
        buildFile << """
            ${componentUnderTestDsl}.targetMachines = [machines.${currentOsFamilyName}().x86(), machines.${currentOsFamilyName}().x86_64()]
        """

        and:
        run "visualStudio"

        then:
        result.assertTasksExecuted(":visualStudio", ":appVisualStudioSolution", projectTasks)

        and:
        projectFile.assertHasComponentSources(componentUnderTest, "src/main")

        def contexts = VariantContext.of(VariantDimension.of("buildType", ['debug', 'release']), VariantDimension.of("architecture", ['x86', 'x86-64']))
        projectFile.projectConfigurations.size() == contexts.size()

        contexts.each {
            assert projectFile.projectConfigurations[it.asVariantName].includePath == filePath(expectedBaseIncludePaths)
            assert projectFile.projectConfigurations[it.asVariantName].buildCommand.endsWith("gradle\" :${getIdeBuildTaskName(it.asVariantName)}")
            assert projectFile.projectConfigurations[it.asVariantName].outputFile == getBuildFile(it)
        }

        and:
        solutionFile.assertHasProjects(visualStudioProjectName)
        solutionFile.assertReferencesProject(projectFile, contexts*.asVariantName as Set)
    }

    String getRootProjectName() {
        return "app"
    }

    SolutionFile getSolutionFile() {
        return solutionFile("${rootProjectName}.sln")
    }

    ProjectFile getProjectFile() {
        return projectFile("${visualStudioProjectName}.vcxproj")
    }

    abstract String getBuildFile(VariantContext variantContext)

    abstract void makeSingleProject()

    abstract String getVisualStudioProjectName()

    abstract String getComponentUnderTestDsl()

    abstract CppSourceElement getComponentUnderTest()

    abstract String getIdeBuildTaskName(String variant)

    String[] getProjectTasks() {
        return [":${visualStudioProjectName}VisualStudioProject", ":${visualStudioProjectName}VisualStudioFilters"]
    }

    List<String> getExpectedBaseIncludePaths() {
        return ["src/main/headers"]
    }

    protected String stripped(String configurationName) {
        if (toolChain.visualCpp) {
            return ""
        } else {
            return configurationName.startsWith("release") ? "stripped/" : ""
        }
    }
}