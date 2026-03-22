package com.jdbcchecker.spec.extractor

import com.jdbcchecker.model.JdbcVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class JdkVersionMappingTest {

    @ParameterizedTest
    @CsvSource(
        "1.1, V1_0",
        "1.2, V2_0",
        "1.4, V3_0",
        "1.6, V4_0",
        "6,   V4_0",
        "1.7, V4_1",
        "7,   V4_1",
        "1.8, V4_2",
        "8,   V4_2",
        "9,   V4_3",
        "11,  V4_3",
        "21,  V4_3",
    )
    fun `should map JDK version to correct JDBC version`(jdkVersion: String, expected: JdbcVersion) {
        assertThat(JdkVersionMapping.toJdbcVersion(jdkVersion)).isEqualTo(expected)
    }

    @Test
    fun `should return null for unknown version`() {
        assertThat(JdkVersionMapping.toJdbcVersion("99")).isNull()
    }

    @Test
    fun `should use fallback for unknown version`() {
        assertThat(JdkVersionMapping.toJdbcVersion("99", JdbcVersion.V1_0))
            .isEqualTo(JdbcVersion.V1_0)
    }

    @Test
    fun `should trim whitespace from version string`() {
        assertThat(JdkVersionMapping.toJdbcVersion(" 1.2 ")).isEqualTo(JdbcVersion.V2_0)
    }
}
