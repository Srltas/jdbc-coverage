package com.jdbcchecker.spec

import com.jdbcchecker.model.JdbcVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdbcSpecLoaderTest {

    private val loader = JdbcSpecLoader()

    @Test
    fun `should load Connection interface from bundled resources`() {
        val methods = loader.loadInterface("java.sql.Connection")
        assertThat(methods).isNotEmpty
        assertThat(methods.first().interfaceName).isEqualTo("java.sql.Connection")
    }

    @Test
    fun `should parse method signatures correctly`() {
        val methods = loader.loadInterface("java.sql.Connection")
        val commit = methods.find { it.methodName == "commit" && it.parameterTypes.isEmpty() }
        assertThat(commit).isNotNull
        assertThat(commit!!.returnType).isEqualTo("void")
        assertThat(commit.jdbcVersion).isEqualTo(JdbcVersion.V1_0)
    }

    @Test
    fun `should parse overloaded methods with parameters`() {
        val methods = loader.loadInterface("java.sql.Connection")
        val prepareStatements = methods.filter { it.methodName == "prepareStatement" }
        assertThat(prepareStatements.size).isGreaterThanOrEqualTo(2)

        val basic = prepareStatements.find { it.parameterTypes == listOf("String") }
        assertThat(basic).isNotNull
        assertThat(basic!!.jdbcVersion).isEqualTo(JdbcVersion.V1_0)

        val withResultSetType = prepareStatements.find {
            it.parameterTypes == listOf("String", "int", "int")
        }
        assertThat(withResultSetType).isNotNull
        assertThat(withResultSetType!!.jdbcVersion).isEqualTo(JdbcVersion.V2_0)
    }

    @Test
    fun `should load all interfaces from bundled resources`() {
        val allMethods = loader.loadAll()
        assertThat(allMethods.size).isGreaterThan(500)
        val interfaces = allMethods.map { it.interfaceName }.distinct()
        assertThat(interfaces).contains(
            "java.sql.Connection",
            "java.sql.Statement",
            "java.sql.ResultSet",
        )
    }

    @Test
    fun `should return empty list for unknown interface`() {
        val methods = loader.loadInterface("com.nonexistent.Interface")
        assertThat(methods).isEmpty()
    }

    @Test
    fun `should correctly assign JDBC versions across multiple versions`() {
        val methods = loader.loadInterface("java.sql.Connection")
        val jdbc40methods = methods.filter { it.jdbcVersion == JdbcVersion.V4_0 }
        val jdbc41methods = methods.filter { it.jdbcVersion == JdbcVersion.V4_1 }

        assertThat(jdbc40methods.map { it.methodName }).contains("createBlob", "createClob")
        assertThat(jdbc41methods.map { it.methodName }).contains("setSchema", "getSchema")
    }

    @Test
    fun `should parse YAML from input stream`() {
        val yaml = """
            interfaceName: java.sql.Test
            since: 1.0
            methodCount: 2
            methods:
            - name: doSomething
              params: [String, int]
              returns: boolean
              since: 2.0
            - name: doNothing
              params: []
              returns: void
              since: 1.0
        """.trimIndent()

        val methods = loader.parseYaml("java.sql.Test", yaml.byteInputStream())
        assertThat(methods).hasSize(2)

        val doSomething = methods[0]
        assertThat(doSomething.methodName).isEqualTo("doSomething")
        assertThat(doSomething.parameterTypes).containsExactly("String", "int")
        assertThat(doSomething.returnType).isEqualTo("boolean")
        assertThat(doSomething.jdbcVersion).isEqualTo(JdbcVersion.V2_0)

        val doNothing = methods[1]
        assertThat(doNothing.methodName).isEqualTo("doNothing")
        assertThat(doNothing.parameterTypes).isEmpty()
        assertThat(doNothing.returnType).isEqualTo("void")
        assertThat(doNothing.jdbcVersion).isEqualTo(JdbcVersion.V1_0)
    }

    @Test
    fun `spec scope excludes non-driver interfaces`() {
        val interfaces = loader.loadAll().map { it.interfaceName }.distinct()
        assertThat(interfaces).doesNotContain(
            "java.sql.SQLData",
            "javax.sql.ConnectionEventListener",
            "javax.sql.StatementEventListener",
            "java.sql.NClob",
            "java.sql.ShardingKey",
        )
        assertThat(interfaces).hasSize(34)
    }

    @Test
    fun `frozen spec-1 interface list is pinned`() {
        assertThat(JdbcSpecLoader.JDBC_INTERFACES).hasSize(34)
        assertThat(JdbcSpecLoader.JDBC_INTERFACES).doesNotContain(
            "java.sql.SQLData",
            "javax.sql.ConnectionEventListener",
            "javax.sql.StatementEventListener",
            "java.sql.NClob",
            "java.sql.ShardingKey",
        )
    }

    @Test
    fun `frozen spec-1 has exactly 889 methods`() {
        assertThat(loader.loadAll()).hasSize(889)
    }

    @Test
    fun `spec version constant is spec-1`() {
        assertThat(JdbcSpecLoader.SPEC_VERSION).isEqualTo("spec-1")
    }
}
