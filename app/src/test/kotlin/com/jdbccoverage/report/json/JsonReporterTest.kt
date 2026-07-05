package com.jdbccoverage.report.json

import com.jdbccoverage.model.AnalysisReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class JsonReporterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun emptyReport() = AnalysisReport(
        driverName = "X",
        sourcePath = "/tmp/src",
        analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
        interfaces = emptyList(),
    )

    @Test
    fun `creates missing parent directories before writing`() {
        val nested = tempDir.resolve("history/latest/x.json")
        JsonReporter().report(emptyReport(), nested)
        assertThat(nested).exists()
        assertThat(JsonReporter().loadReport(nested).driverName).isEqualTo("X")
    }
}
