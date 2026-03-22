package com.jdbcchecker.model

/**
 * JDBC specification versions mapped to their corresponding Java versions.
 */
enum class JdbcVersion(val display: String, val javaVersion: String) {
    V1_0("1.0", "JDK 1.1"),
    V2_0("2.0", "JDK 1.2"),
    V3_0("3.0", "JDK 1.4"),
    V4_0("4.0", "Java 6"),
    V4_1("4.1", "Java 7"),
    V4_2("4.2", "Java 8"),
    V4_3("4.3", "Java 9"),
    ;

    companion object {
        fun fromString(value: String): JdbcVersion? =
            entries.find { it.display == value }
    }
}
