package com.jdbccoverage.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdbcVersionTest {

    @Test
    fun `should parse version from string`() {
        assertThat(JdbcVersion.fromString("4.2")).isEqualTo(JdbcVersion.V4_2)
        assertThat(JdbcVersion.fromString("1.0")).isEqualTo(JdbcVersion.V1_0)
    }

    @Test
    fun `should return null for unknown version`() {
        assertThat(JdbcVersion.fromString("5.0")).isNull()
        assertThat(JdbcVersion.fromString("")).isNull()
    }

    @Test
    fun `should have correct display names`() {
        assertThat(JdbcVersion.V4_3.display).isEqualTo("4.3")
        assertThat(JdbcVersion.V4_3.javaVersion).isEqualTo("Java 9")
    }
}
