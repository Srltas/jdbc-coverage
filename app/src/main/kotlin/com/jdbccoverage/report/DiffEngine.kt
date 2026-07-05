package com.jdbccoverage.report

import com.jdbccoverage.model.AnalysisReport
import com.jdbccoverage.model.DiffReport
import com.jdbccoverage.model.ImplementationStatus
import com.jdbccoverage.model.MethodChangeType
import com.jdbccoverage.model.MethodDiff
import com.jdbccoverage.model.MethodSignature

/**
 * Computes a diff between two [AnalysisReport] instances.
 *
 * Methods present in only one report are included with [ImplementationStatus.NotFound]
 * as the missing side's status.
 */
fun computeDiff(baseline: AnalysisReport, current: AnalysisReport): DiffReport {
    fun AnalysisReport.methodMap(): Map<String, Pair<String, MethodSignature>> =
        interfaces.flatMap { iface ->
            iface.methods.map { method ->
                val globalKey = "${iface.interfaceName}::${method.specMethod.matchKey}"
                globalKey to Pair(
                    method.status.key,
                    method.specMethod,
                )
            }
        }.toMap()

    fun AnalysisReport.statusMap(): Map<String, ImplementationStatus> =
        interfaces.flatMap { iface ->
            iface.methods.map { method ->
                "${iface.interfaceName}::${method.specMethod.matchKey}" to method.status
            }
        }.toMap()

    val baselineInfo = baseline.methodMap()
    val currentInfo = current.methodMap()
    val baselineStatus = baseline.statusMap()
    val currentStatus = current.statusMap()

    val allKeys = (baselineInfo.keys + currentInfo.keys).toSet()

    val diffs = allKeys.mapNotNull { key ->
        val specMethod = (currentInfo[key]?.second ?: baselineInfo[key]?.second) ?: return@mapNotNull null
        val before = baselineStatus[key] ?: ImplementationStatus.NotFound
        val after = currentStatus[key] ?: ImplementationStatus.NotFound

        val change = when {
            before == after -> MethodChangeType.UNCHANGED
            after.score() > before.score() -> MethodChangeType.IMPROVED
            else -> MethodChangeType.REGRESSED
        }

        MethodDiff(
            interfaceName = specMethod.interfaceName,
            methodDisplayName = specMethod.displayName,
            jdbcVersion = specMethod.jdbcVersion,
            before = before,
            after = after,
            change = change,
        )
    }.sortedWith(
        compareBy({ it.change.ordinal }, { it.jdbcVersion.ordinal }, { it.methodDisplayName }),
    )

    return DiffReport(
        baselineDriverName = baseline.driverName,
        currentDriverName = current.driverName,
        baselineAnalyzedAt = baseline.analyzedAt,
        currentAnalyzedAt = current.analyzedAt,
        baselineCoverage = baseline.overallCoveragePercent,
        currentCoverage = current.overallCoveragePercent,
        diffs = diffs,
    )
}
