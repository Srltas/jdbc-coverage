package com.jdbcchecker.cli

import com.jdbcchecker.git.GitCloneService
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a source specifier (local path or Git URL) to a local filesystem path.
 *
 * Tracks temporary directories created for Git clones and cleans them up on [close].
 * Use inside a `use {}` block to ensure cleanup.
 */
class SourceResolver : Closeable {

    private val tempDirs = mutableListOf<Path>()

    /**
     * Resolve a source specifier to a local path.
     *
     * - If [source] is a Git URL, it is shallow-cloned to a temp directory.
     * - If [source] is a local path, it is returned as-is.
     * - If [subdir] is specified, it is appended to the resolved path.
     *
     * @param source local path string or Git URL
     * @param branch Git branch or tag (ignored for local paths)
     * @param subdir subdirectory within the repo to use as source root
     * @return resolved local path
     */
    fun resolve(source: String, branch: String? = null, subdir: String? = null): Path {
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

        return if (subdir != null) basePath.resolve(subdir) else basePath
    }

    override fun close() {
        tempDirs.forEach { dir ->
            dir.toFile().deleteRecursively()
        }
    }
}
