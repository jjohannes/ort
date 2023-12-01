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

package org.ossreviewtoolkit.plugins.packagemanagers.node.utils

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.model.readTree
import org.ossreviewtoolkit.utils.common.collectMessages

/**
 * A class to detect the package managers used for the give [definitionFiles].
 */
class NpmDetection(private val definitionFiles: Collection<File>) {
    /**
     * A map of project directories to the set of package managers that are most likely responsible for the project. If
     * the set is empty, none of the package managers is responsible.
     */
    private val projectDirManagers: Map<File, Set<NodePackageManager>> by lazy {
        definitionFiles.associate { file ->
            val projectDir = file.parentFile
            projectDir to NodePackageManager.forDirectory(projectDir)
        }
    }

    /**
     * A map of project directories to the set of workspace patterns for the project. If a project directory does not
     * define a workspace, the list of patterns is empty.
     */
    private val workspacePatterns: Map<File, List<PathMatcher>> by lazy {
        definitionFiles.associate { file ->
            val projectDir = file.parentFile
            val patterns = NodePackageManager.entries.mapNotNull { it.getWorkspaces(projectDir) }.flatten()
            projectDir to patterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        }
    }

    /**
     * Return the roots of the workspace that [projectDir] is part of. It could be multiple roots if multiple package
     * managers define overlapping workspaces. If [projectDir] does not belong to any workspace, the empty set is
     * returned.
     */
    private fun getWorkspaceRoots(projectDir: File): Set<File> =
        workspacePatterns.filter { (_, patterns) ->
            patterns.any { it.matches(projectDir.toPath()) }
        }.keys

    /**
     * Return those [definitionFiles] that define root projects for the given [manager].
     */
    fun filterApplicable(manager: NodePackageManager): List<File> =
        definitionFiles.filter { file ->
            val projectDir = file.parentFile

            // Try to clearly determine the package manager from specific files.
            val managersFromFiles = projectDirManagers[projectDir].orEmpty()
            when {
                manager !in managersFromFiles -> return@filter false
                managersFromFiles.size == 1 -> {
                    logger.info { "Detected '$file' to be the root of a(n) $manager project." }
                    return@filter true
                }
            }

            // Fall back to determining the package manager from workspaces.
            val managersFromWorkspaces = getWorkspaceRoots(projectDir).mapNotNull {
                projectDirManagers[it]
            }.flatten()

            when {
                manager !in managersFromWorkspaces -> return@filter false
                managersFromWorkspaces.size == 1 -> {
                    logger.info { "Skipping '$file' as it is part of an implicitly handled $manager workspace." }
                    return@filter false
                }
            }

            logger.warn {
                val managers = managersFromFiles + managersFromWorkspaces
                "Ignoring '$file' directory as the package manager is unclear. It could be any of " +
                    "${managers.joinToString()}."
            }

            false
        }
}

/**
 * An enum of all supported Node package managers.
 */
enum class NodePackageManager(
    val lockFileName: String,
    val markerFileName: String? = null,
    val workspaceFileName: String = NodePackageManager.DEFINITION_FILE
) {
    NPM("package-lock.json", markerFileName = "npm-shrinkwrap.json") {
        override fun hasLockFile(projectDir: File): Boolean =
            super.hasLockFile(projectDir) || hasNonEmptyFile(projectDir, markerFileName)
    },

    PNPM("pnpm-lock.yaml", workspaceFileName = "pnpm-workspace.yaml") {
        override fun getWorkspaces(projectDir: File): List<String>? {
            val workspaceFile = projectDir.resolve(workspaceFileName)
            if (!workspaceFile.isFile) return null

            val packages = runCatching {
                workspaceFile.readTree().get("packages")
            }.onFailure {
                logger.error { "Failed to parse '$workspaceFile': ${it.collectMessages()}" }
            }.getOrNull() ?: return null

            return packages.map { "${workspaceFile.parentFile.invariantSeparatorsPath}/${it.textValue()}" }
        }
    },

    YARN("yarn.lock") {
        private val lockFileMarker = "# yarn lockfile v1"

        override fun hasLockFile(projectDir: File): Boolean {
            val lockFile = projectDir.resolve(lockFileName)
            if (!lockFile.isFile) return false

            return lockFile.useLines { lines ->
                lines.take(2).lastOrNull() == lockFileMarker
            }
        }
    },

    YARN2("yarn.lock", markerFileName = ".yarnrc.yml") {
        private val lockFileMarker = "__metadata:"

        override fun hasLockFile(projectDir: File): Boolean {
            val lockFile = projectDir.resolve(lockFileName)
            if (!lockFile.isFile) return false

            return lockFile.useLines { lines ->
                lines.take(4).lastOrNull() == lockFileMarker
            }
        }
    };

    companion object {
        /**
         * The name of the definition file used by all Node package managers.
         */
        const val DEFINITION_FILE = "package.json"

        /**
         * A regular expression to find an asterisk that is not surrounded by another asterisk.
         */
        private val WORKSPACES_SINGLE_ASTERISK_REGEX = Regex("(?<!\\*)\\*(?!\\*)")

        /**
         * Return the set of package managers that are most likely responsible for the given [projectDir].
         */
        fun forDirectory(projectDir: File): Set<NodePackageManager> {
            val scores = NodePackageManager.entries.associateWith {
                it.getFileScore(projectDir)
            }

            // Get the overall maximum score.
            val maxScore = scores.maxBy { it.value }

            // Get all package managers with the maximum score.
            return scores.filter { it.value == maxScore.value }.keys
        }
    }

    /**
     * Return true if the [projectDir] contains a lock file for this package manager, or return false otherwise.
     */
    open fun hasLockFile(projectDir: File): Boolean = hasNonEmptyFile(projectDir, lockFileName)

    /**
     * If the [projectDir] contains a workspace file for this package manager, return the list of package patterns, or
     * return null otherwise.
     */
    open fun getWorkspaces(projectDir: File): List<String>? {
        val workspaceFile = projectDir.resolve(workspaceFileName)
        if (!workspaceFile.isFile) return null

        val workspaces = runCatching {
            workspaceFile.readTree().get("workspaces")
        }.onFailure {
            logger.error { "Failed to parse '$workspaceFile': ${it.collectMessages()}" }
        }.getOrNull() ?: return null

        val packages = when {
            workspaces.isArray -> workspaces
            workspaces.isObject -> workspaces["packages"]
            else -> null
        } ?: run {
            logger.warn { "Unable to read workspaces from '$workspaceFile'." }
            return null
        }

        return packages.map {
            val pattern = "${workspaceFile.parentFile.invariantSeparatorsPath}/${it.textValue()}"

            // NPM and Yarn treat "*" as an alias for "**", so replace any single "*" with "**".
            pattern.replace(WORKSPACES_SINGLE_ASTERISK_REGEX, "**")
        }
    }

    /**
     * Return a score for the [projectDir] based on the presence of files specific to this package manager.
     * The higher the score, the more likely it is that the [projectDir] is managed by this package manager.
     */
    fun getFileScore(projectDir: File): Int =
        listOf(
            hasLockFile(projectDir),
            hasNonEmptyFile(projectDir, markerFileName),
            // Only count the presence of an additional workspace file if it is not the definition file.
            workspaceFileName != DEFINITION_FILE && hasNonEmptyFile(projectDir, workspaceFileName)
        ).count { it }
}

/**
 * Return true if [fileName] is not null and the [projectDir] contains a non-empty file named [fileName], or return
 * false otherwise.
 */
private fun hasNonEmptyFile(projectDir: File, fileName: String?): Boolean =
    fileName != null && projectDir.resolve(fileName).let { it.isFile && it.length() > 0 }
