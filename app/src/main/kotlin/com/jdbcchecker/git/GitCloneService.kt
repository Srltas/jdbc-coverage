package com.jdbcchecker.git

import java.nio.file.Path

/**
 * Clones Git repositories using the system `git` command.
 *
 * Uses shallow clone (depth 1) by default to minimize download time and disk usage.
 */
object GitCloneService {

    /**
     * Shallow-clone a Git repository.
     *
     * @param url Git repository URL (HTTPS or SSH)
     * @param targetDir local directory to clone into
     * @param branch specific branch or tag to clone (null = default branch)
     * @return the cloned directory path
     * @throws RuntimeException if git clone fails
     */
    fun clone(url: String, targetDir: Path, branch: String? = null): Path {
        val command = mutableListOf("git", "clone", "--depth", "1")
        if (branch != null) {
            command.addAll(listOf("--branch", branch))
        }
        command.addAll(listOf(url, targetDir.toString()))

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("git clone failed (exit code $exitCode):\n$output")
        }

        return targetDir
    }

    /** Checks whether a string looks like a Git repository URL. */
    fun isGitUrl(source: String): Boolean =
        source.startsWith("https://") ||
            source.startsWith("http://") ||
            source.startsWith("git@") ||
            (source.contains("/") && source.endsWith(".git") && !source.startsWith("/"))
}
