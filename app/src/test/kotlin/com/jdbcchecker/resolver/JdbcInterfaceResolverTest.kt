package com.jdbcchecker.resolver

import com.github.javaparser.StaticJavaParser
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JdbcInterfaceResolverTest {

    @Test
    fun `stale entry-class pin fails hard instead of falling back`() {
        val cu = StaticJavaParser.parse(
            "package d; public class C implements java.sql.Wrapper { }",
        )
        assertThatThrownBy {
            JdbcInterfaceResolver().resolve(
                listOf(cu),
                overrides = mapOf("java.sql.Connection" to "d.MissingClass"),
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("d.MissingClass")
            .hasMessageContaining("java.sql.Connection")
    }
}
