package com.jdbccoverage.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MethodSignatureTest {

    @Test
    fun `should generate correct display name`() {
        val method = MethodSignature(
            interfaceName = "java.sql.Connection",
            methodName = "setSchema",
            parameterTypes = listOf("String"),
            returnType = "void",
            jdbcVersion = JdbcVersion.V4_2,
        )

        assertThat(method.displayName).isEqualTo("Connection.setSchema(String)")
    }

    @Test
    fun `should generate correct match key`() {
        val method = MethodSignature(
            interfaceName = "java.sql.Connection",
            methodName = "prepareStatement",
            parameterTypes = listOf("String", "int"),
            returnType = "PreparedStatement",
            jdbcVersion = JdbcVersion.V1_0,
        )

        assertThat(method.matchKey).isEqualTo("prepareStatement(String,int)")
    }

    @Test
    fun `should handle no-arg method`() {
        val method = MethodSignature(
            interfaceName = "java.sql.Connection",
            methodName = "getAutoCommit",
            parameterTypes = emptyList(),
            returnType = "boolean",
            jdbcVersion = JdbcVersion.V1_0,
        )

        assertThat(method.displayName).isEqualTo("Connection.getAutoCommit()")
        assertThat(method.matchKey).isEqualTo("getAutoCommit()")
    }
}
