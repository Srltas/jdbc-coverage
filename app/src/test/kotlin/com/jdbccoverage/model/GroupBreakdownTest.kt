package com.jdbccoverage.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GroupBreakdownTest {

    private fun iface(name: String, vararg statuses: ImplementationStatus) = InterfaceResult(
        interfaceName = name,
        implementingClass = "d.Impl",
        methods = statuses.map {
            MethodResult(MethodSignature(name, "m", emptyList(), "void", JdbcVersion.V1_0), it)
        },
    )

    @Test
    fun `headline coverage counts only the MAIN group - groupBreakdown splits all three`() {
        val report = AnalysisReport(
            driverName = "X",
            sourcePath = "s",
            analyzedAt = Instant.parse("2026-07-06T00:00:00Z"),
            interfaces = listOf(
                // MAIN: 1 implemented of 2
                iface("java.sql.Connection", ImplementationStatus.FullyImplemented, ImplementationStatus.NotFound),
                // PERIPHERAL: 0 of 1
                iface("java.sql.SQLInput", ImplementationStatus.NotFound),
                // XA: 1 of 1
                iface("javax.transaction.xa.XAResource", ImplementationStatus.FullyImplemented),
            ),
        )

        // Headline reflects MAIN only — the XA/peripheral methods do not inflate it.
        assertThat(report.totalMethods).isEqualTo(2)
        assertThat(report.totalImplemented).isEqualTo(1)
        assertThat(report.overallCoveragePercent).isEqualTo(50.0)

        val g = report.groupBreakdown
        assertThat(g.getValue(SpecGroup.MAIN).implemented).isEqualTo(1)
        assertThat(g.getValue(SpecGroup.MAIN).total).isEqualTo(2)
        assertThat(g.getValue(SpecGroup.PERIPHERAL).implemented).isEqualTo(0)
        assertThat(g.getValue(SpecGroup.PERIPHERAL).total).isEqualTo(1)
        assertThat(g.getValue(SpecGroup.XA).implemented).isEqualTo(1)
        assertThat(g.getValue(SpecGroup.XA).total).isEqualTo(1)
        assertThat(g.getValue(SpecGroup.XA).coveragePercent).isEqualTo(100.0)
    }
}
