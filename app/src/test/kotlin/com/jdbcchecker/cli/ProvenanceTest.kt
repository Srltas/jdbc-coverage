package com.jdbcchecker.cli

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.report.json.JsonReporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ProvenanceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `provenance fields round-trip through JSON`() {
        val report = AnalysisReport(
            driverName = "X",
            sourcePath = "s",
            analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
            interfaces = emptyList(),
            profileUsed = "cubrid",
            specVersion = "spec-1",
            toolVersion = TOOL_VERSION,
            sourceCommit = "a".repeat(40),
        )
        val path = tempDir.resolve("r.json")
        JsonReporter().report(report, path)
        val loaded = JsonReporter().loadReport(path)
        assertThat(loaded.specVersion).isEqualTo("spec-1")
        assertThat(loaded.toolVersion).isEqualTo(TOOL_VERSION)
        assertThat(loaded.sourceCommit).isEqualTo("a".repeat(40))
    }

    @Test
    fun `resolveSourceCommit returns a 40-hex hash inside a git repo`() {
        // This test runs inside the project repo, which is a git repository.
        val commit = resolveSourceCommit(Path.of("."))
        assertThat(commit).isNotNull().matches("[0-9a-f]{40}")
    }

    @Test
    fun `resolveSourceCommit returns null outside a git repo`() {
        assertThat(resolveSourceCommit(tempDir)).isNull()
    }
}
