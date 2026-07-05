package com.jdbcchecker.profile

import com.jdbcchecker.report.json.createYamlObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files

class DriverProfileLoaderTest {

    private val mapper = createYamlObjectMapper()

    @Test
    fun `parses a complete profile YAML`() {
        val yaml = """
            name: mssql
            displayName: "Microsoft SQL Server JDBC"
            detectBy:
              packagePrefix:
                - com.microsoft.sqlserver.jdbc
            entryClasses:
              java.sql.Connection: com.microsoft.sqlserver.jdbc.SQLServerConnection43
              java.sql.Statement: com.microsoft.sqlserver.jdbc.SQLServerStatement
            stubHelpers:
              - callPattern: SQLServerException.throwNotSupportedException
                classify: THROWS_SQL_EXCEPTION
            stubExceptionClasses:
              - SomeDriverException
        """.trimIndent()

        val profile = mapper.readValue(yaml.byteInputStream(), DriverProfile::class.java)

        assertThat(profile.name).isEqualTo("mssql")
        assertThat(profile.displayName).isEqualTo("Microsoft SQL Server JDBC")
        assertThat(profile.detectBy.packagePrefix).containsExactly("com.microsoft.sqlserver.jdbc")
        assertThat(profile.entryClasses)
            .containsEntry("java.sql.Connection", "com.microsoft.sqlserver.jdbc.SQLServerConnection43")
            .containsEntry("java.sql.Statement", "com.microsoft.sqlserver.jdbc.SQLServerStatement")
        assertThat(profile.stubHelpers).hasSize(1)
        assertThat(profile.stubHelpers[0].callPattern)
            .isEqualTo("SQLServerException.throwNotSupportedException")
        assertThat(profile.stubHelpers[0].classify).isEqualTo(ClassifyAs.THROWS_SQL_EXCEPTION)
        assertThat(profile.stubExceptionClasses).containsExactly("SomeDriverException")
    }

    @Test
    fun `parses minimal profile with only name and displayName`() {
        val yaml = """
            name: cubrid
            displayName: "CUBRID JDBC"
        """.trimIndent()

        val profile = mapper.readValue(yaml.byteInputStream(), DriverProfile::class.java)

        assertThat(profile.name).isEqualTo("cubrid")
        assertThat(profile.detectBy.packagePrefix).isEmpty()
        assertThat(profile.entryClasses).isEmpty()
        assertThat(profile.stubHelpers).isEmpty()
        assertThat(profile.stubExceptionClasses).isEmpty()
    }

    @Test
    fun `ignores unknown YAML properties`() {
        val yaml = """
            name: x
            displayName: "X"
            unknownField: "should be ignored"
            futureExtension:
              someKey: someValue
        """.trimIndent()

        val profile = mapper.readValue(yaml.byteInputStream(), DriverProfile::class.java)

        assertThat(profile.name).isEqualTo("x")
    }

    @Test
    fun `loadFromFile reads YAML from disk`() {
        val tmp = Files.createTempFile("profile", ".yaml")
        try {
            Files.writeString(
                tmp,
                """
                name: custom
                displayName: "Custom Driver"
                detectBy:
                  packagePrefix:
                    - com.example.driver
                """.trimIndent(),
            )
            val profile = DriverProfileLoader(mapper).loadFromFile(tmp)
            assertThat(profile.name).isEqualTo("custom")
            assertThat(profile.detectBy.packagePrefix).containsExactly("com.example.driver")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `loadFromFile throws when file does not exist`() {
        val missing = Files.createTempFile("nonexistent", ".yaml")
        Files.deleteIfExists(missing) // remove so it's actually missing

        assertThatThrownBy { DriverProfileLoader(mapper).loadFromFile(missing) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not found")
    }

    @Test
    fun `loadFromFile throws on missing required name field`() {
        val tmp = Files.createTempFile("profile", ".yaml")
        try {
            Files.writeString(
                tmp,
                """
                displayName: "No name"
                """.trimIndent(),
            )
            assertThatThrownBy { DriverProfileLoader(mapper).loadFromFile(tmp) }
                .isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `loadBundled returns null for unknown profile`() {
        // No bundled profiles exist in resources yet (Step 2 adds them) — null is expected.
        // After Step 2 lands, this name is still bogus, so should still return null.
        val profile = DriverProfileLoader(mapper).loadBundled("does-not-exist-anywhere")
        assertThat(profile).isNull()
    }
}
