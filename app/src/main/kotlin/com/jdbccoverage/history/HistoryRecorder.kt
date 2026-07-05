package com.jdbccoverage.history

import com.fasterxml.jackson.databind.SerializationFeature
import com.jdbccoverage.model.AnalysisReport
import com.jdbccoverage.model.MethodChangeType
import com.jdbccoverage.report.computeDiff
import com.jdbccoverage.report.json.JsonReporter
import com.jdbccoverage.report.json.createObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Records one driver's daily analysis into a history directory:
 *
 *   <historyDir>/history/<slug>.jsonl   one compact line per day, kept forever
 *   <historyDir>/latest/<slug>.json     full report, overwritten each run
 *
 * The delta in each line is computed against the previous `latest/` report
 * BEFORE overwriting it. When the spec version differs from the previous run,
 * no delta is computed (`specChanged=true`) — those diffs would be phantom.
 */
class HistoryRecorder(private val historyDir: Path) {

    private val lineMapper = createObjectMapper().copy().disable(SerializationFeature.INDENT_OUTPUT)
    private val jsonReporter = JsonReporter()

    fun record(report: AnalysisReport, date: LocalDate = LocalDate.now()): HistoryEntry {
        val slug = slugOf(report.driverName)
        val latestPath = historyDir.resolve("latest").resolve("$slug.json")
        val previous = if (Files.isRegularFile(latestPath)) jsonReporter.loadReport(latestPath) else null

        val specChanged = previous != null && previous.specVersion != report.specVersion
        val changes = if (previous != null && !specChanged) {
            computeDiff(previous, report).diffs
                .filter { it.change != MethodChangeType.UNCHANGED }
                .map { MethodChange(it.interfaceName, it.methodDisplayName, it.before.key, it.after.key) }
        } else {
            emptyList()
        }

        val entry = HistoryEntry(
            date = date.toString(),
            driverName = report.driverName,
            specVersion = report.specVersion,
            toolVersion = report.toolVersion,
            sourceCommit = report.sourceCommit,
            profileUsed = report.profileUsed,
            overallPercent = report.overallCoveragePercent,
            totalMethods = report.totalMethods,
            implemented = report.totalImplemented,
            stub = report.totalStub,
            notFound = report.totalNotFound,
            cumulative = report.cumulativeCoverage.map {
                CumulativePoint(it.version.display, it.implemented, it.total)
            },
            specChanged = specChanged,
            changes = changes,
        )

        val jsonlPath = historyDir.resolve("history").resolve("$slug.jsonl")
        Files.createDirectories(jsonlPath.parent)
        // Per-line defensive parse: a corrupt line (partial write, hand edit) is
        // dropped with a warning instead of poisoning every future daily run.
        val kept = if (Files.exists(jsonlPath)) {
            Files.readAllLines(jsonlPath)
                .filter { it.isNotBlank() }
                .filter { line ->
                    val parsed = try {
                        lineMapper.readValue(line, HistoryEntry::class.java)
                    } catch (e: Exception) {
                        System.err.println(
                            "Warning: dropping corrupt history line in $jsonlPath: ${e.message?.take(80)}",
                        )
                        return@filter false
                    }
                    parsed.date != entry.date
                }
        } else {
            emptyList()
        }
        Files.write(jsonlPath, kept + lineMapper.writeValueAsString(entry))

        jsonReporter.report(report, latestPath)
        return entry
    }

    companion object {
        /** "CUBRID JDBC" → "cubrid-jdbc": stable filename key per driver name. */
        fun slugOf(driverName: String): String =
            driverName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
