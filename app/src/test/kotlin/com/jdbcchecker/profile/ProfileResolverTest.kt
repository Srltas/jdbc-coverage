package com.jdbcchecker.profile

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProfileResolverTest {

    private val resolver = ProfileResolver()

    private fun parseUnit(pkg: String, className: String = "C"): CompilationUnit {
        return StaticJavaParser.parse("package $pkg; public class $className {}")
    }

    // ── precedence ──────────────────────────────────────────────────────────

    @Test
    fun `profileName loads bundled profile`() {
        val profile = resolver.resolve(
            compilationUnits = emptyList(),
            profileName = "mssql",
        )
        assertThat(profile?.name).isEqualTo("mssql")
        assertThat(profile?.displayName).isEqualTo("Microsoft SQL Server JDBC")
    }

    @Test
    fun `profileName throws when bundled profile missing`() {
        assertThatThrownBy {
            resolver.resolve(
                compilationUnits = emptyList(),
                profileName = "totally-bogus-name",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown driver profile")
    }

    @Test
    fun `auto-detect returns null when no source packages match`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("com.unknown.driver")),
        )
        assertThat(profile).isNull()
    }

    // ── auto-detect by package prefix ───────────────────────────────────────

    @Test
    fun `auto-detect picks mssql for SQLServer package`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("com.microsoft.sqlserver.jdbc")),
        )
        assertThat(profile?.name).isEqualTo("mssql")
    }

    @Test
    fun `auto-detect picks mysql for com_mysql_cj package`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("com.mysql.cj.jdbc")),
        )
        assertThat(profile?.name).isEqualTo("mysql")
    }

    @Test
    fun `auto-detect picks pgjdbc for org_postgresql package`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("org.postgresql.jdbc")),
        )
        assertThat(profile?.name).isEqualTo("pgjdbc")
    }

    @Test
    fun `auto-detect picks mariadb for org_mariadb_jdbc package`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("org.mariadb.jdbc")),
        )
        assertThat(profile?.name).isEqualTo("mariadb")
    }

    @Test
    fun `auto-detect picks cubrid for cubrid_jdbc package`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("cubrid.jdbc.driver")),
        )
        assertThat(profile?.name).isEqualTo("cubrid")
    }

    @Test
    fun `auto-detect matches when source has subpackage of detectBy prefix`() {
        val profile = resolver.resolve(
            compilationUnits = listOf(parseUnit("com.microsoft.sqlserver.jdbc.dataclassification")),
        )
        assertThat(profile?.name).isEqualTo("mssql")
    }

    @Test
    fun `auto-detect ignores empty package CUs`() {
        // No package declaration → no match → null
        val cu = StaticJavaParser.parse("public class C {}")
        val profile = resolver.resolve(compilationUnits = listOf(cu))
        assertThat(profile).isNull()
    }
}
