package com.jdbcchecker.spec.extractor

import com.jdbcchecker.model.JdbcVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SpecExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        // Create a minimal JDBC-like interface for testing
        val sqlDir = tempDir.resolve("java/sql")
        Files.createDirectories(sqlDir)

        Files.writeString(
            sqlDir.resolve("TestConnection.java"),
            """
            package java.sql;

            /**
             * Test connection interface.
             * @since 1.1
             */
            public interface TestConnection {

                /**
                 * Get auto commit mode.
                 * @return true if auto commit
                 * @since 1.1
                 */
                boolean getAutoCommit();

                /**
                 * Set auto commit mode.
                 * @param autoCommit the mode
                 * @since 1.1
                 */
                void setAutoCommit(boolean autoCommit);

                /**
                 * Set schema.
                 * @param schema the schema name
                 * @since 1.7
                 */
                void setSchema(String schema);

                /**
                 * Create a savepoint.
                 * @param name savepoint name
                 * @return the savepoint
                 * @since 1.4
                 */
                Object setSavepoint(String name);
            }
            """.trimIndent()
        )
    }

    @Test
    fun `should extract methods from interface source`() {
        val extractor = SpecExtractor(tempDir)
        val methods = extractor.extractAll()

        assertThat(methods).hasSize(4)
        assertThat(methods.map { it.methodName }).containsExactlyInAnyOrder(
            "getAutoCommit", "setAutoCommit", "setSchema", "setSavepoint"
        )
    }

    @Test
    fun `should map since tags to correct JDBC versions`() {
        val extractor = SpecExtractor(tempDir)
        val methods = extractor.extractAll()
        val methodMap = methods.associateBy { it.methodName }

        assertThat(methodMap["getAutoCommit"]?.jdbcVersion).isEqualTo(JdbcVersion.V1_0)
        assertThat(methodMap["setAutoCommit"]?.jdbcVersion).isEqualTo(JdbcVersion.V1_0)
        assertThat(methodMap["setSchema"]?.jdbcVersion).isEqualTo(JdbcVersion.V4_1)
        assertThat(methodMap["setSavepoint"]?.jdbcVersion).isEqualTo(JdbcVersion.V3_0)
    }

    @Test
    fun `should extract correct parameter types`() {
        val extractor = SpecExtractor(tempDir)
        val methods = extractor.extractAll()
        val methodMap = methods.associateBy { it.methodName }

        assertThat(methodMap["getAutoCommit"]?.parameterTypes).isEmpty()
        assertThat(methodMap["setAutoCommit"]?.parameterTypes).containsExactly("boolean")
        assertThat(methodMap["setSchema"]?.parameterTypes).containsExactly("String")
        assertThat(methodMap["setSavepoint"]?.parameterTypes).containsExactly("String")
    }

    @Test
    fun `should extract correct return types`() {
        val extractor = SpecExtractor(tempDir)
        val methods = extractor.extractAll()
        val methodMap = methods.associateBy { it.methodName }

        assertThat(methodMap["getAutoCommit"]?.returnType).isEqualTo("boolean")
        assertThat(methodMap["setAutoCommit"]?.returnType).isEqualTo("void")
        assertThat(methodMap["setSavepoint"]?.returnType).isEqualTo("Object")
    }

    @Test
    fun `should set correct interface name`() {
        val extractor = SpecExtractor(tempDir)
        val methods = extractor.extractAll()

        assertThat(methods).allMatch { it.interfaceName == "java.sql.TestConnection" }
    }

    @Test
    fun `should filter by target interfaces`() {
        val extractor = SpecExtractor(tempDir)
        val methods = extractor.extractAll(setOf("java.sql.NonExistent"))

        assertThat(methods).isEmpty()
    }
}
