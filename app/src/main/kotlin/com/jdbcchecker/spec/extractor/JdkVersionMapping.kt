package com.jdbcchecker.spec.extractor

import com.jdbcchecker.model.JdbcVersion

/**
 * Maps JDK @since version tags to JDBC specification versions.
 *
 * JDK source uses Java platform versions in @since tags (e.g., "1.2", "1.4", "1.6").
 * This maps those to the corresponding JDBC specification version.
 */
object JdkVersionMapping {

    private val MAPPING = mapOf(
        // JDBC 1.0 — JDK 1.1
        "1.1" to JdbcVersion.V1_0,

        // JDBC 2.0 — JDK 1.2
        "1.2" to JdbcVersion.V2_0,

        // JDBC 3.0 — JDK 1.4
        "1.4" to JdbcVersion.V3_0,

        // JDBC 4.0 — Java 6
        "1.6" to JdbcVersion.V4_0,
        "6" to JdbcVersion.V4_0,

        // JDBC 4.1 — Java 7
        "1.7" to JdbcVersion.V4_1,
        "7" to JdbcVersion.V4_1,

        // JDBC 4.2 — Java 8
        "1.8" to JdbcVersion.V4_2,
        "8" to JdbcVersion.V4_2,

        // JDBC 4.3 — Java 9+
        // JDBC 4.3 (JSR 221) was finalized with Java SE 9 and remains the latest
        // ratified JDBC specification as of Java 25. New JDK releases are mapped
        // here as they are confirmed to not introduce a new JDBC specification version.
        "9" to JdbcVersion.V4_3,
        "10" to JdbcVersion.V4_3,
        "11" to JdbcVersion.V4_3,
        "12" to JdbcVersion.V4_3,
        "13" to JdbcVersion.V4_3,
        "14" to JdbcVersion.V4_3,
        "15" to JdbcVersion.V4_3,
        "16" to JdbcVersion.V4_3,
        "17" to JdbcVersion.V4_3,
        "18" to JdbcVersion.V4_3,
        "19" to JdbcVersion.V4_3,
        "20" to JdbcVersion.V4_3,
        "21" to JdbcVersion.V4_3,
        "22" to JdbcVersion.V4_3,
        "23" to JdbcVersion.V4_3,
        "24" to JdbcVersion.V4_3,
        "25" to JdbcVersion.V4_3,
    )

    /**
     * Convert a @since tag value to a JDBC version.
     * Returns null if the version is unrecognized.
     */
    fun toJdbcVersion(sinceTag: String): JdbcVersion? {
        val trimmed = sinceTag.trim()
        return MAPPING[trimmed]
    }

    /**
     * Convert a @since tag value to a JDBC version,
     * using a fallback if the version is unrecognized.
     */
    fun toJdbcVersion(sinceTag: String, fallback: JdbcVersion): JdbcVersion {
        return toJdbcVersion(sinceTag) ?: fallback
    }
}
