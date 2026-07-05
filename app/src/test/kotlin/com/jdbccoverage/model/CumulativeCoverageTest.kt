package com.jdbccoverage.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CumulativeCoverageTest {

    private fun method(name: String, version: JdbcVersion, status: ImplementationStatus) = MethodResult(
        specMethod = MethodSignature("java.sql.Connection", name, emptyList(), "void", version),
        status = status,
    )

    @Test
    fun `cumulative coverage accumulates methods up to each boundary`() {
        val report = AnalysisReport(
            driverName = "X",
            sourcePath = "s",
            analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
            interfaces = listOf(
                InterfaceResult(
                    interfaceName = "java.sql.Connection",
                    implementingClass = "d.C",
                    methods = listOf(
                        method("a", JdbcVersion.V1_0, ImplementationStatus.FullyImplemented),
                        method("b", JdbcVersion.V1_0, ImplementationStatus.ThrowsUnsupported),
                        method("c", JdbcVersion.V4_2, ImplementationStatus.FullyImplemented),
                        method("d", JdbcVersion.V4_3, ImplementationStatus.NotFound),
                    ),
                ),
            ),
        )

        val cumulative = report.cumulativeCoverage
        // Boundaries only for versions present in the spec set: 1.0, 4.2, 4.3
        assertThat(cumulative.map { it.version })
            .containsExactly(JdbcVersion.V1_0, JdbcVersion.V4_2, JdbcVersion.V4_3)

        val v10 = cumulative[0]
        assertThat(v10.total).isEqualTo(2)
        assertThat(v10.implemented).isEqualTo(1)
        assertThat(v10.coveragePercent).isEqualTo(50.0)

        val v42 = cumulative[1]
        assertThat(v42.total).isEqualTo(3)
        assertThat(v42.implemented).isEqualTo(2)

        val v43 = cumulative[2]
        assertThat(v43.total).isEqualTo(4)
        assertThat(v43.implemented).isEqualTo(2)
    }
}
