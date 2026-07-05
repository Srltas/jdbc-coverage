package com.jdbccoverage.report.dashboard

import com.jdbccoverage.history.CumulativePoint
import com.jdbccoverage.history.HistoryEntry
import com.jdbccoverage.history.MethodChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DashboardRendererTest {

    private fun entry(date: String, percent: Double, changes: List<MethodChange> = emptyList()) = HistoryEntry(
        date = date,
        driverName = "CUBRID JDBC",
        specVersion = "spec-1",
        toolVersion = "2.0.0",
        sourceCommit = "abc1234def567890abc1234def567890abc1234d",
        profileUsed = "cubrid",
        overallPercent = percent,
        totalMethods = 889,
        implemented = (889 * percent / 100).toInt(),
        stub = 300,
        notFound = 200,
        cumulative = listOf(CumulativePoint("4.2", 340, 849)),
        changes = changes,
    )

    @Test
    fun `renders a self-contained html with chart, table, and changes`() {
        val html = DashboardRenderer().render(
            listOf(
                DriverDashboardData(
                    slug = "cubrid-jdbc",
                    entries = listOf(
                        entry("2026-07-04", 40.0),
                        entry(
                            "2026-07-05",
                            41.0,
                            changes = listOf(
                                MethodChange("java.sql.Connection", "Connection.setSchema(String)", "NOT_FOUND", "FULLY_IMPLEMENTED"),
                            ),
                        ),
                    ),
                    latest = null,
                ),
            ),
        )

        assertThat(html).contains("<svg")
        assertThat(html).contains("CUBRID JDBC")
        assertThat(html).contains("Connection.setSchema(String)")
        assertThat(html).contains("41.0")
        // Self-contained: no external loads. (The SVG namespace URI string is
        // allowed — it is an identifier, not a network request.)
        assertThat(html).doesNotContain("<link", "<script src", "fetch(", "import(", "url(http")
        // Data embedded as JSON, not string-concatenated into JS
        assertThat(html).contains("application/json")
    }
}
