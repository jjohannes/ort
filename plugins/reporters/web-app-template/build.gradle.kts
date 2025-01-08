/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

import java.util.Locale

import org.apache.tools.ant.taskdefs.condition.Os

import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsSetupTask
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnSetupTask

// The Kotlin/JS plugins are only applied programmatically for Kotlin projects that target JavaScript. As we do not
// directly target JavaScript from Kotlin, manually apply the plugins and configure the tool versions.
NodeJsRootPlugin.apply(rootProject).version = "22.13.0"

// The Yarn plugin registers tasks always on the root project, see
// https://github.com/JetBrains/kotlin/blob/2.1.0/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/js/yarn/YarnPlugin.kt#L158-L162
YarnPlugin.apply(rootProject).version = "1.22.22"

val kotlinNodeJsSetup by rootProject.tasks.existing(NodeJsSetupTask::class)
val kotlinYarnSetup by rootProject.tasks.existing(YarnSetupTask::class)

@Suppress("DEPRECATION") // Cannot use `destinationProvider` as it is internal.
val nodeDir = kotlinNodeJsSetup.get().destination
val nodeBinDir = if (Os.isFamily(Os.FAMILY_WINDOWS)) nodeDir else nodeDir.resolve("bin")
val nodeExecutable = if (Os.isFamily(Os.FAMILY_WINDOWS)) nodeBinDir.resolve("node.exe") else nodeBinDir.resolve("node")

@Suppress("DEPRECATION") // Cannot use `destinationProvider` as it is internal.
val yarnDir = kotlinYarnSetup.get().destination
val yarnJs = yarnDir.resolve("bin/yarn.js")

tasks.addRule("Pattern: yarn<Command>") {
    val taskName = this
    if (taskName.startsWith("yarn")) {
        val command = taskName.removePrefix("yarn").replaceFirstChar { it.lowercase(Locale.ROOT) }

        tasks.register<Exec>(taskName) {
            // Execute the Yarn version downloaded by Gradle using the NodeJs version downloaded by Gradle.
            commandLine = listOf(nodeExecutable.path, yarnJs.path, command)

            val oldPath = System.getenv("PATH")
            val newPath = listOf(
                // Prepend the directory of the bootstrapped Node.js to the PATH environment.
                nodeBinDir.path,
                // Prepend the directory of additional tools like "rescripts" to the PATH environment.
                projectDir.resolve("node_modules/.bin").path,
                oldPath
            ).joinToString(File.pathSeparator)

            environment = environment + ("PATH" to newPath)
        }
    }
}

/*
 * Further configure rule tasks, e.g. with inputs and outputs.
 */

val yarnInstall = tasks.named("yarnInstall") {
    description = "Use Yarn to install the Node.js dependencies."
    group = "Node"

    dependsOn(kotlinYarnSetup)

    inputs.files(".yarnrc", "package.json", "yarn.lock")

    // Note that "node_modules" cannot be cached due to symlinks, see https://github.com/gradle/gradle/issues/3525.
    outputs.dir("node_modules")
}

val yarnBuild = tasks.named("yarnBuild") {
    description = "Use Yarn to build the Node.js application."
    group = "Node"

    inputs.files(yarnInstall)
    inputs.dir("src")

    outputs.cacheIf { true }
    outputs.dir("build")
}

val yarnLint = tasks.named("yarnLint") {
    description = "Let Yarn run the linter to check for style issues."
    group = "Node"

    dependsOn(yarnInstall)
}

/*
 * Resemble the Java plugin tasks for convenience.
 */

tasks.register("build") {
    dependsOn(yarnBuild, yarnLint)
}

tasks.register("check") {
    dependsOn(yarnLint)
}

tasks.register<Delete>("clean") {
    delete("build")
    delete("node_modules")
    delete("yarn-error.log")
}

val webAppTemplateConfiguration by configurations.creating {
    isCanBeResolved = false
}

artifacts {
    add(webAppTemplateConfiguration.name, yarnBuild)
}
