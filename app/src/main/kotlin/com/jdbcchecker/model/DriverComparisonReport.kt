package com.jdbcchecker.model

import java.time.Instant

/**
 * Coverage summary for a single driver within a multi-driver comparison.
 *
 * @property driverName Human-readable driver name
 * @property sourcePath Absolute path to the analyzed source directory
 * @property coveragePercent Overall coverage percentage
 * @property interfaceCoverages Per-interface coverage, keyed by simple interface name
 */
data class DriverSummary(
    val driverName: String,
    val sourcePath: String,
    val coveragePercent: Double,
    val interfaceCoverages: Map<String, Double>,
)

/**
 * Per-method status row in a multi-driver comparison.
 *
 * @property interfaceName Fully qualified JDBC interface name
 * @property methodDisplayName Human-readable method signature
 * @property jdbcVersion JDBC version that introduced this method
 * @property statuses Implementation status per driver, keyed by driver name
 */
data class MethodComparisonRow(
    val interfaceName: String,
    val methodDisplayName: String,
    val jdbcVersion: JdbcVersion,
    val statuses: Map<String, ImplementationStatus>,
)

/**
 * Comparison report across two or more JDBC drivers analyzed from source.
 *
 * @property drivers Ordered list of driver summaries
 * @property methodRows Per-method comparison rows covering all spec methods
 * @property analyzedAt Timestamp when the comparison was generated
 */
data class DriverComparisonReport(
    val drivers: List<DriverSummary>,
    val methodRows: List<MethodComparisonRow>,
    val analyzedAt: Instant = Instant.now(),
)
