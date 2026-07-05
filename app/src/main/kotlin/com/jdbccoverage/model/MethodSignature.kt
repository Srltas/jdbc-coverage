package com.jdbccoverage.model

/**
 * Represents a JDBC interface method from the specification.
 *
 * @property interfaceName Fully qualified interface name (e.g., "java.sql.Connection")
 * @property methodName Method name (e.g., "setSchema")
 * @property parameterTypes Ordered list of parameter types (e.g., ["String"])
 * @property returnType Return type (e.g., "void")
 * @property jdbcVersion JDBC version that introduced this method
 */
data class MethodSignature(
    val interfaceName: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val jdbcVersion: JdbcVersion,
) {
    /** Short display name: "Connection.setSchema(String)" */
    val displayName: String
        get() {
            val simpleName = interfaceName.substringAfterLast('.')
            val params = parameterTypes.joinToString(", ")
            return "$simpleName.$methodName($params)"
        }

    /** Unique key for matching: "methodName(type1,type2)" */
    val matchKey: String
        get() = "$methodName(${parameterTypes.joinToString(",")})"
}
