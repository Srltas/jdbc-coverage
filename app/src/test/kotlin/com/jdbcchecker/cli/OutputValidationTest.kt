package com.jdbcchecker.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutputValidationTest {

    @Test
    fun `accepts console and json outputs`() {
        assertThat(validateOutputs(listOf("console", "json:/tmp/r.json"))).isEmpty()
    }

    @Test
    fun `rejects unknown formats and empty json path`() {
        val errors = validateOutputs(listOf("html:/tmp/r.html", "json:", "bogus"))
        assertThat(errors).hasSize(3)
        assertThat(errors[0]).contains("html:/tmp/r.html")
    }
}
