package com.jdbcchecker.profile

import com.jdbcchecker.report.json.createYamlObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
    fun `loadBundled returns null for unknown profile`() {
        // No bundled profiles exist in resources yet (Step 2 adds them) — null is expected.
        // After Step 2 lands, this name is still bogus, so should still return null.
        val profile = DriverProfileLoader(mapper).loadBundled("does-not-exist-anywhere")
        assertThat(profile).isNull()
    }
}
