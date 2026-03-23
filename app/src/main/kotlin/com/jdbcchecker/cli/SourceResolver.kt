package com.jdbcchecker.cli

import com.jdbcchecker.git.GitCloneService
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a source specifier (local path or Git URL) to one or more local filesystem paths.
 *
 * Tracks temporary directories created for Git clones and cleans them up on [close].
 * Use inside a `use {}` block to ensure cleanup.
 */
class SourceResolver : Closeable {

    private val tempDirs = mutableListOf<Path>()

    /**
     * Resolve a source specifier to a list of local paths.
     *
     * - If [source] is a Git URL, it is shallow-cloned to a temp directory.
     * - If [source] is a local path, it is used as-is.
     * - If [subdirs] is empty, the base path itself is returned as a single-element list.
     * - If [subdirs] is non-empty, each subdir is resolved relative to the base path.
     *
     * @param source local path string or Git URL
     * @param branch Git branch or tag (ignored for local paths)
     * @param subdirs subdirectories within the repo to use as source roots (multiple allowed)
     * @return list of resolved local paths
     */
    fun resolve(source: String, branch: String? = null, subdirs: List<String> = emptyList()): List<Path> {
        val basePath = if (GitCloneService.isGitUrl(source)) {
            val tempDir = Files.createTempDirectory("jdbc-checker-")
            tempDirs.add(tempDir)
            print("Cloning $source ... ")
            GitCloneService.clone(source, tempDir, branch)
            println("done")
            tempDir
        } else {
            Path.of(source)
        }

        return if (subdirs.isEmpty()) {
            listOf(basePath)
        } else {
            subdirs.map { basePath.resolve(it) }
        }
    }

    override fun close() {
        tempDirs.forEach { dir ->
            dir.toFile().deleteRecursively()
        }
    }
}
