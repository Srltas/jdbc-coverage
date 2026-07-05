package com.jdbcchecker.history

/** Cumulative coverage at one version boundary, projected for the JSONL line. */
data class CumulativePoint(
    val version: String,
    val implemented: Int,
    val total: Int,
)

/** One method whose status changed since the previous recorded run. */
data class MethodChange(
    val interfaceName: String,
    val method: String,
    val before: String,
    val after: String,
)

/**
 * One line of `history/<slug>.jsonl` — the permanent, compact daily record.
 * Full per-method detail lives only in `latest/<slug>.json` (overwritten daily).
 */
data class HistoryEntry(
    val date: String,
    val driverName: String,
    val specVersion: String,
    val toolVersion: String,
    val sourceCommit: String?,
    val profileUsed: String?,
    val overallPercent: Double,
    val totalMethods: Int,
    val implemented: Int,
    val stub: Int,
    val notFound: Int,
    val cumulative: List<CumulativePoint>,
    val specChanged: Boolean = false,
    val changes: List<MethodChange> = emptyList(),
)
