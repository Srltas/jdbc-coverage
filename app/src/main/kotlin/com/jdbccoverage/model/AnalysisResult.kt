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
    /**
     * The headline metric (total counts, overall %, version/cumulative/status breakdowns)
     * describes the [SpecGroup.MAIN] group only. [PERIPHERAL] and [XA] are reported via
     * [groupBreakdown]. `interfaces` still holds every measured interface.
     */
    private val mainInterfaces: List<InterfaceResult>
        get() = interfaces.filter { JdbcScope.groupOf(it.interfaceName) == SpecGroup.MAIN }

    private val mainMethods: List<MethodResult>
        get() = mainInterfaces.flatMap { it.methods }

    val totalMethods: Int get() = mainInterfaces.sumOf { it.total }
    val totalImplemented: Int get() = mainInterfaces.sumOf { it.implemented }
    val totalStub: Int get() = mainInterfaces.sumOf { it.stub }
    val totalNotFound: Int get() = mainInterfaces.sumOf { it.notFound }
    val overallCoveragePercent: Double
        get() = if (totalMethods == 0) 0.0 else (totalImplemented.toDouble() / totalMethods) * 100.0

    /** Coverage per scope group ([JdbcScope]): MAIN is the headline; PERIPHERAL and XA are reported alongside. */
    val groupBreakdown: Map<SpecGroup, GroupCoverage>
        get() = SpecGroup.entries.associateWith { group ->
            val methods = interfaces
                .filter { JdbcScope.groupOf(it.interfaceName) == group }
                .flatMap { it.methods }
            GroupCoverage(
                group = group,
                total = methods.size,
                implemented = methods.count { it.status.isImplemented() },
                stub = methods.count { it.status.isStub() },
                notFound = methods.count { it.status is ImplementationStatus.NotFound },
            )
        }

    /** Coverage breakdown by JDBC version (MAIN group only) */
    val versionBreakdown: Map<JdbcVersion, VersionCoverage>
        get() {
            val allMethods = mainMethods
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

    /** Level 2 status distribution across MAIN-group methods */
    val statusDistribution: Map<String, Int>
        get() {
            val allMethods = mainMethods
            return allMethods.groupBy { it.status.label }.mapValues { it.value.size }
        }

    /**
     * Cumulative coverage at each JDBC version boundary present in the MAIN group:
     * "of all methods introduced at or before version V, how many are implemented".
     * This is the number to watch while expanding toward a target version (e.g. 4.2).
     */
    val cumulativeCoverage: List<CumulativeCoverage>
        get() {
            val allMethods = mainMethods
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
 * Coverage stats for one scope group ([SpecGroup]).
 */
data class GroupCoverage(
    val group: SpecGroup,
    val total: Int,
    val implemented: Int,
    val stub: Int,
    val notFound: Int,
) {
    val coveragePercent: Double
        get() = if (total == 0) 0.0 else (implemented.toDouble() / total) * 100.0
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

