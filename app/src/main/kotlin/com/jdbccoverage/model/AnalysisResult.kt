package com.jdbccoverage.model

import java.time.Instant

/**
 * Result of analyzing a single method.
 */
data class MethodResult(
    val specMethod: MethodSignature,
    val status: ImplementationStatus,
    val implementingClass: String? = null,
    val sourceLocation: String? = null,
)

/**
 * Result of analyzing a single JDBC interface.
 */
data class InterfaceResult(
    val interfaceName: String,
    val implementingClass: String?,
    val methods: List<MethodResult>,
) {
    val total: Int get() = methods.size
    val implemented: Int get() = methods.count { it.status.isImplemented() }
    val stub: Int get() = methods.count { it.status.isStub() }
    val notFound: Int get() = methods.count { it.status is ImplementationStatus.NotFound }
    val coveragePercent: Double
        get() = if (total == 0) 0.0 else (implemented.toDouble() / total) * 100.0
}

/**
 * Complete analysis result for a driver.
 *
 * @property profileUsed name of the driver profile that was applied to this
 *     analysis (e.g., "mssql"), or null if profiles were disabled or no
 *     bundled profile matched. Used by reporters to display the active
 *     profile and by diff/compare to record provenance in serialized JSON.
 */
data class AnalysisReport(
    val driverName: String,
    val sourcePath: String,
    val analyzedAt: Instant,
    val interfaces: List<InterfaceResult>,
    val profileUsed: String? = null,
    /** Frozen spec identifier this snapshot was measured against (e.g. "spec-1"). */
    val specVersion: String = "",
    /** Tool version that produced this snapshot. */
    val toolVersion: String = "",
    /** `git rev-parse HEAD` of the analyzed source tree; null when not a git checkout. */
    val sourceCommit: String? = null,
) {
    val totalMethods: Int get() = interfaces.sumOf { it.total }
    val totalImplemented: Int get() = interfaces.sumOf { it.implemented }
    val totalStub: Int get() = interfaces.sumOf { it.stub }
    val totalNotFound: Int get() = interfaces.sumOf { it.notFound }
    val overallCoveragePercent: Double
        get() = if (totalMethods == 0) 0.0 else (totalImplemented.toDouble() / totalMethods) * 100.0

    /** Coverage breakdown by JDBC version */
    val versionBreakdown: Map<JdbcVersion, VersionCoverage>
        get() {
            val allMethods = interfaces.flatMap { it.methods }
            return JdbcVersion.entries.associateWith { version ->
                val versionMethods = allMethods.filter { it.specMethod.jdbcVersion == version }
                VersionCoverage(
                    total = versionMethods.size,
                    implemented = versionMethods.count { it.status.isImplemented() },
                    stub = versionMethods.count { it.status.isStub() },
                    notFound = versionMethods.count { it.status is ImplementationStatus.NotFound },
                )
            }.filter { it.value.total > 0 }
        }

    /** Level 2 status distribution across all methods */
    val statusDistribution: Map<String, Int>
        get() {
            val allMethods = interfaces.flatMap { it.methods }
            return allMethods.groupBy { it.status.label }.mapValues { it.value.size }
        }

    /**
     * Cumulative coverage at each JDBC version boundary present in the spec set:
     * "of all methods introduced at or before version V, how many are implemented".
     * This is the number to watch while expanding toward a target version (e.g. 4.2).
     */
    val cumulativeCoverage: List<CumulativeCoverage>
        get() {
            val allMethods = interfaces.flatMap { it.methods }
            return JdbcVersion.entries
                .filter { v -> allMethods.any { it.specMethod.jdbcVersion == v } }
                .map { boundary ->
                    val upTo = allMethods.filter { it.specMethod.jdbcVersion.ordinal <= boundary.ordinal }
                    CumulativeCoverage(
                        version = boundary,
                        total = upTo.size,
                        implemented = upTo.count { it.status.isImplemented() },
                    )
                }
        }
}

/**
 * Coverage stats for a single JDBC version.
 */
data class VersionCoverage(
    val total: Int,
    val implemented: Int,
    val stub: Int,
    val notFound: Int,
) {
    val coveragePercent: Double
        get() = if (total == 0) 0.0 else (implemented.toDouble() / total) * 100.0
}

/**
 * Cumulative coverage of all methods introduced at or before [version].
 */
data class CumulativeCoverage(
    val version: JdbcVersion,
    val total: Int,
    val implemented: Int,
) {
    val coveragePercent: Double
        get() = if (total == 0) 0.0 else (implemented.toDouble() / total) * 100.0
}

