package com.jdbcchecker.history

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.JdbcVersion
import com.jdbcchecker.model.MethodResult
import com.jdbcchecker.model.MethodSignature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

class HistoryRecorderTest {

    @TempDir
    lateinit var dir: Path

    private fun report(status: ImplementationStatus, specVersion: String = "spec-1"): AnalysisReport {
        val method = MethodResult(
            specMethod = MethodSignature("java.sql.Connection", "commit", emptyList(), "void", JdbcVersion.V1_0),
            status = status,
        )
        return AnalysisReport(
            driverName = "CUBRID JDBC",
            sourcePath = "/tmp/src",
            analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
            interfaces = listOf(InterfaceResult("java.sql.Connection", "d.C", listOf(method))),
            profileUsed = "cubrid",
            specVersion = specVersion,
            toolVersion = "2.0.0",
            sourceCommit = null,
        )
    }

    @Test
    fun `slug is stable and filesystem-safe`() {
        assertThat(HistoryRecorder.slugOf("CUBRID JDBC")).isEqualTo("cubrid-jdbc")
        assertThat(HistoryRecorder.slugOf("MySQL Connector/J")).isEqualTo("mysql-connector-j")
    }

    @Test
    fun `first run records an entry without changes and writes latest`() {
        val entry = HistoryRecorder(dir).record(report(ImplementationStatus.ThrowsUnsupported), LocalDate.parse("2026-07-05"))

        assertThat(entry.changes).isEmpty()
        assertThat(entry.specChanged).isFalse()
        assertThat(entry.overallPercent).isEqualTo(0.0)
        assertThat(dir.resolve("history/cubrid-jdbc.jsonl")).exists()
        assertThat(dir.resolve("latest/cubrid-jdbc.json")).exists()
        assertThat(Files.readAllLines(dir.resolve("history/cubrid-jdbc.jsonl"))).hasSize(1)
    }

    @Test
    fun `second run records status transitions as changes`() {
        val recorder = HistoryRecorder(dir)
        recorder.record(report(ImplementationStatus.ThrowsUnsupported), LocalDate.parse("2026-07-05"))
        val entry = recorder.record(report(ImplementationStatus.FullyImplemented), LocalDate.parse("2026-07-06"))

        assertThat(entry.changes).hasSize(1)
        assertThat(entry.changes[0].before).isEqualTo("THROWS_UNSUPPORTED")
        assertThat(entry.changes[0].after).isEqualTo("FULLY_IMPLEMENTED")
        assertThat(entry.overallPercent).isEqualTo(100.0)
        assertThat(Files.readAllLines(dir.resolve("history/cubrid-jdbc.jsonl"))).hasSize(2)
    }

    @Test
    fun `same-day rerun replaces that day's line`() {
        val recorder = HistoryRecorder(dir)
        recorder.record(report(ImplementationStatus.ThrowsUnsupported), LocalDate.parse("2026-07-05"))
        recorder.record(report(ImplementationStatus.FullyImplemented), LocalDate.parse("2026-07-05"))

        val lines = Files.readAllLines(dir.resolve("history/cubrid-jdbc.jsonl")).filter { it.isNotBlank() }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("100.0")
    }

    @Test
    fun `spec version mismatch records specChanged without deltas`() {
        val recorder = HistoryRecorder(dir)
        recorder.record(report(ImplementationStatus.ThrowsUnsupported, specVersion = "spec-1"), LocalDate.parse("2026-07-05"))
        val entry = recorder.record(report(ImplementationStatus.FullyImplemented, specVersion = "spec-2"), LocalDate.parse("2026-07-06"))

        assertThat(entry.specChanged).isTrue()
        assertThat(entry.changes).isEmpty()
    }
}
