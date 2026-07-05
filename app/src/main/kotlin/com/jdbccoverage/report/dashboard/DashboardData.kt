package com.jdbccoverage.report.dashboard

import com.fasterxml.jackson.databind.SerializationFeature
import com.jdbccoverage.history.HistoryEntry
import com.jdbccoverage.model.AnalysisReport
import com.jdbccoverage.report.json.JsonReporter
import com.jdbccoverage.report.json.createObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/** One driver's full dashboard input: permanent history + optional latest full report. */
data class DriverDashboardData(
    val slug: String,
    val entries: List<HistoryEntry>,
    val latest: AnalysisReport?,
)

object DashboardData {

    /**
     * Load every driver found under `<historyDir>/history` as `*.jsonl` files, sorted by
     * slug so palette slots stay stable across runs. Entries are sorted by date.
     */
    fun load(historyDir: Path): List<DriverDashboardData> {
        val lineMapper = createObjectMapper().copy().disable(SerializationFeature.INDENT_OUTPUT)
        val historySub = historyDir.resolve("history")
        if (!Files.isDirectory(historySub)) return emptyList()

        return Files.list(historySub).use { stream ->
            stream.asSequence()
                .filter { it.fileName.toString().endsWith(".jsonl") }
                .sortedBy { it.fileName.toString() }
                .map { file ->
                    val slug = file.fileName.toString().removeSuffix(".jsonl")
                    val entries = Files.readAllLines(file)
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            try {
                                lineMapper.readValue(line, HistoryEntry::class.java)
                            } catch (e: Exception) {
                                System.err.println(
                                    "Warning: skipping corrupt history line in $file: ${e.message?.take(80)}",
                                )
                                null
                            }
                        }
                        .sortedBy { it.date }
                    val latestPath = historyDir.resolve("latest").resolve("$slug.json")
                    val latest = if (Files.isRegularFile(latestPath)) JsonReporter().loadReport(latestPath) else null
                    DriverDashboardData(slug, entries, latest)
                }
                .filter { it.entries.isNotEmpty() }
                .toList()
        }
    }
}
