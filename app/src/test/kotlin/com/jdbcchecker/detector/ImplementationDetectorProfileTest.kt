package com.jdbcchecker.detector

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.profile.ClassifyAs
import com.jdbcchecker.profile.StubHelper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests profile-driven extensions to ImplementationDetector:
 *   - stubHelpers (matchStubHelper)
 *   - extraStubExceptionClasses (matchesExtraStubException)
 *
 * Regression coverage for the generic behavior lives in
 * ImplementationDetectorTest.
 */
class ImplementationDetectorProfileTest {

    private fun parseMethod(code: String): MethodDeclaration {
        val cu = StaticJavaParser.parse("class Test { $code }")
        return cu.findAll(MethodDeclaration::class.java).first()
    }

    // ── stubHelpers ─────────────────────────────────────────────────────────

    @Test
    fun `MSSQL throwNotSupportedException helper classifies single-line method as stub`() {
        val detector = ImplementationDetector(
            stubHelpers = listOf(
                StubHelper("SQLServerException.throwNotSupportedException", ClassifyAs.THROWS_SQL_EXCEPTION),
            ),
        )
        val method = parseMethod("""
            public final void setRef(int i, java.sql.Ref x) throws SQLException {
                SQLServerException.throwNotSupportedException(connection, this);
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `stub helper recognised even with logging and setup before the call`() {
        val detector = ImplementationDetector(
            stubHelpers = listOf(
                StubHelper("SQLServerException.throwNotSupportedException", ClassifyAs.THROWS_SQL_EXCEPTION),
            ),
        )
        val method = parseMethod("""
            public final void createArrayOf(String t, Object[] e) throws SQLException {
                if (loggerExternal.isLoggable(Level.FINER)) {
                    loggerExternal.entering(getClassNameLogging(), "createArrayOf");
                }
                SQLServerException.throwNotSupportedException(this, null);
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `stub helper match honors classify configuration`() {
        val detector = ImplementationDetector(
            stubHelpers = listOf(
                StubHelper("FooHelper.boom", ClassifyAs.THROWS_UNSUPPORTED),
            ),
        )
        val method = parseMethod("""
            public void doIt() {
                FooHelper.boom();
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ThrowsUnsupported)
    }

    @Test
    fun `method with stub helper AND a return path is not classified as stub`() {
        // Some code paths return normally → method is at least Partial, not pure stub.
        val detector = ImplementationDetector(
            stubHelpers = listOf(
                StubHelper("SQLServerException.throwNotSupportedException", ClassifyAs.THROWS_SQL_EXCEPTION),
            ),
        )
        val method = parseMethod("""
            public Object weird() {
                if (cond) return real();
                SQLServerException.throwNotSupportedException(this, null);
                return null;
            }
        """)
        // Has an early return → throw-only check fails. Falls to PARTIAL/FULL.
        // (The stub helper still doesn't introduce a return-killing throw, so this is partial.)
        assertThat(detector.analyzeMethodBody(method))
            .isIn(ImplementationStatus.FullyImplemented, ImplementationStatus.Partial)
    }

    @Test
    fun `without stubHelpers configured the same call falls to generic classification`() {
        // Same body, no profile — falls back to generic behavior. The generic
        // detector lands on Partial here because the call expression
        // "SQLServerException.throwNotSupportedException(...)" textually
        // contains both "SQLException" (from the class name) and "not
        // supported" (lower-cased "notsupported" from the helper name), which
        // matches isUnsupportedThrow's heuristic. With the profile,
        // classifyAsThrowOnlyIfApplicable wins first and produces a more
        // specific ThrowsSqlException — that's the point of the profile.
        val detector = ImplementationDetector()
        val method = parseMethod("""
            public final void setRef(int i, java.sql.Ref x) throws SQLException {
                SQLServerException.throwNotSupportedException(connection, this);
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.Partial)
    }

    // ── extraStubExceptionClasses ───────────────────────────────────────────

    @Test
    fun `extra stub exception class name classified as stub on throw`() {
        val detector = ImplementationDetector(
            extraStubExceptionClasses = listOf("MyDriverNotSupported"),
        )
        val method = parseMethod("""
            public void thing() {
                throw new MyDriverNotSupported("nope");
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `extra stub exception names use word boundaries`() {
        // "Foo" must not match inside "FooBar".
        val detector = ImplementationDetector(
            extraStubExceptionClasses = listOf("Foo"),
        )
        val method = parseMethod("""
            public void thing() {
                throw new FooBarException("nope");
            }
        """)
        // FooBarException ends in "Exception" but doesn't match Foo word-boundary
        // (Foo is a prefix of FooBar so \\bFoo\\b doesn't match). Falls to
        // ThrowsUnsupported via the default branch.
        assertThat(detector.analyzeMethodBody(method))
            .isIn(ImplementationStatus.ThrowsUnsupported, ImplementationStatus.ThrowsSqlException)
        // Specifically: FooBarException is matched by neither SQL_EXCEPTION_REGEX
        // (no "SQL") nor the built-in stub patterns, and "Foo" word-boundary fails
        // against "FooBar". So result must be ThrowsUnsupported.
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ThrowsUnsupported)
    }
}
