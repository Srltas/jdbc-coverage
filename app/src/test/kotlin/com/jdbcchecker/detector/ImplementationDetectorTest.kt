package com.jdbcchecker.detector

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.jdbcchecker.model.ImplementationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ImplementationDetectorTest {

    private val detector = ImplementationDetector()

    private fun parseMethod(code: String): MethodDeclaration {
        val classCode = "class Test { $code }"
        val cu = StaticJavaParser.parse(classCode)
        return cu.findAll(MethodDeclaration::class.java).first()
    }

    @Test
    fun `should detect UnsupportedOperationException as ThrowsUnsupported`() {
        val method = parseMethod("""
            public void setSchema(String schema) {
                throw new UnsupportedOperationException();
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ThrowsUnsupported)
    }

    @Test
    fun `should detect SQLException throw as ThrowsSqlException`() {
        val method = parseMethod("""
            public void setSchema(String schema) {
                throw new SQLException("Not supported");
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `should detect return null as ReturnsDefault`() {
        val method = parseMethod("""
            public String getSchema() {
                return null;
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `should detect return false as ReturnsDefault`() {
        val method = parseMethod("""
            public boolean isValid(int timeout) {
                return false;
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `should detect multi-statement method as FullyImplemented`() {
        val method = parseMethod("""
            public void setAutoCommit(boolean autoCommit) {
                checkOpen();
                this.autoCommit = autoCommit;
                connection.setAutoCommit(autoCommit);
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.FullyImplemented)
    }

    @Test
    fun `should detect single delegation as Delegates`() {
        val method = parseMethod("""
            public String getSchema() {
                return inner.getSchema();
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.Delegates)
    }

    @Test
    fun `should detect empty body as ReturnsDefault`() {
        val method = parseMethod("""
            public void close() {
            }
        """)

        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ReturnsDefault)
    }
}
