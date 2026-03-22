package com.jdbcchecker.model

import java.time.Instant

/**
 * Describes the direction of change in a method's implementation status
 * between two analysis snapshots.
 */
enum class MethodChangeType(val display: String) {
    IMPROVED("Improved"),
    REGRESSED("Regressed"),
    UNCHANGED("Unchanged"),
}

/**
 * Diff result for a single JDBC method between two analyses.
 *
 * @property interfaceName Fully qualified JDBC interface name
 * @property methodDisplayName Human-readable method signature
 * @property jdbcVersion JDBC version that introduced this method
 * @property before Implementation status in the baseline
 * @property after Implementation status in the current analysis
 * @property change Whether this is an improvement, regression, or no change
 */
data class MethodDiff(
    val interfaceName: String,
    val methodDisplayName: String,
    val jdbcVersion: JdbcVersion,
    val before: ImplementationStatus,
    val after: ImplementationStatus,
    val change: MethodChangeType,
)

/**
 * Full diff report comparing a baseline analysis against a current analysis.
 *
 * @property baselineDriverName Driver name from the baseline report
 * @property currentDriverName Driver name from the current report
 * @property baselineAnalyzedAt Timestamp of the baseline analysis
 * @property currentAnalyzedAt Timestamp of the current analysis
 * @property baselineCoverage Overall coverage percentage in the baseline
 * @property currentCoverage Overall coverage percentage in the current analysis
 * @property diffs Per-method diff entries
 */
data class DiffReport(
    val baselineDriverName: String,
    val currentDriverName: String,
    val baselineAnalyzedAt: Instant,
    val currentAnalyzedAt: Instant,
    val baselineCoverage: Double,
    val currentCoverage: Double,
    val diffs: List<MethodDiff>,
) {
    val coverageDelta: Double get() = currentCoverage - baselineCoverage
    val improved: List<MethodDiff> get() = diffs.filter { it.change == MethodChangeType.IMPROVED }
    val regressed: List<MethodDiff> get() = diffs.filter { it.change == MethodChangeType.REGRESSED }
    val unchanged: List<MethodDiff> get() = diffs.filter { it.change == MethodChangeType.UNCHANGED }
}
