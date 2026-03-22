package com.jdbcchecker.report

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.DiffReport
import com.jdbcchecker.model.DriverComparisonReport
import com.jdbcchecker.model.DriverSummary
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.MethodChangeType
import com.jdbcchecker.model.MethodComparisonRow
import com.jdbcchecker.model.MethodDiff
import com.jdbcchecker.model.MethodSignature

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

/**
 * Computes a side-by-side comparison across two or more [AnalysisReport] instances.
 *
 * Methods missing from a driver are represented as [ImplementationStatus.NotFound].
 */
fun computeComparison(reports: List<AnalysisReport>): DriverComparisonReport {
    require(reports.size >= 2) { "At least 2 reports are required for comparison" }

    data class MethodKey(val interfaceName: String, val matchKey: String)

    val methodIndex = mutableMapOf<MethodKey, MethodSignature>()
    val driverMaps: Map<String, MutableMap<MethodKey, ImplementationStatus>> =
        reports.associate { it.driverName to mutableMapOf() }

    for (report in reports) {
        val driverMap = driverMaps[report.driverName]!!
        for (iface in report.interfaces) {
            for (method in iface.methods) {
                val mk = MethodKey(iface.interfaceName, method.specMethod.matchKey)
                methodIndex[mk] = method.specMethod
                driverMap[mk] = method.status
            }
        }
    }

    val methodRows = methodIndex.entries
        .sortedWith(
            compareBy(
                { it.key.interfaceName },
                { it.value.jdbcVersion.ordinal },
                { it.value.displayName },
            ),
        )
        .map { (mk, spec) ->
            MethodComparisonRow(
                interfaceName = mk.interfaceName,
                methodDisplayName = spec.displayName,
                jdbcVersion = spec.jdbcVersion,
                statuses = reports.associate { report ->
                    report.driverName to (driverMaps[report.driverName]!![mk] ?: ImplementationStatus.NotFound)
                },
            )
        }

    val drivers = reports.map { report ->
        DriverSummary(
            driverName = report.driverName,
            sourcePath = report.sourcePath,
            coveragePercent = report.overallCoveragePercent,
            interfaceCoverages = report.interfaces.associate {
                it.interfaceName.substringAfterLast('.') to it.coveragePercent
            },
        )
    }

    return DriverComparisonReport(drivers = drivers, methodRows = methodRows)
}
