package com.jdbcchecker.model

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
 */
data class AnalysisReport(
    val driverName: String,
    val sourcePath: String,
    val analyzedAt: Instant,
    val interfaces: List<InterfaceResult>,
) {
    val totalMethods: Int get() = interfaces.sumOf { it.total }
    val totalImplemented: Int get() = interfaces.sumOf { it.implemented }
    val totalStub: Int get() = interfaces.sumOf { it.stub }
    val totalNotFound: Int get() = interfaces.sumOf { it.notFound }
    val overallCoveragePercent: Double
        get() = if (totalMethods == 0) 0.0 else (totalImplemented.toDouble() / totalMethods) * 100.0
}

/** Helper extensions for status classification */
private fun ImplementationStatus.isImplemented(): Boolean = when (this) {
    is ImplementationStatus.Delegates,
    is ImplementationStatus.Partial,
    is ImplementationStatus.FullyImplemented -> true
    else -> false
}

private fun ImplementationStatus.isStub(): Boolean = when (this) {
    is ImplementationStatus.ThrowsUnsupported,
    is ImplementationStatus.ThrowsSqlException,
    is ImplementationStatus.ReturnsDefault -> true
    else -> false
}
