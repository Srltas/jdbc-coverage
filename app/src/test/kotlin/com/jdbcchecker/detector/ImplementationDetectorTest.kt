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

    // ── Bug 1: SQLException subclass detection ──────────────────────────────

    @Test
    fun `Bug 1 should classify single-statement SQLServerException throw as stub`() {
        val method = parseMethod("""
            public void execute() {
                throw new SQLServerException(this, "msg", null, 0, false);
            }
        """)
        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `Bug 1 should classify single-statement PSQLException throw as stub`() {
        val method = parseMethod("""
            public void execute() {
                throw new PSQLException("msg", PSQLState.NOT_IMPLEMENTED);
            }
        """)
        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `Bug 1 should classify NotUpdatable throw as stub (driver-specific subclass)`() {
        val method = parseMethod("""
            public void updateInt(int columnIndex, int x) {
                throw new NotUpdatable(Messages.getString("NotUpdatable.0"));
            }
        """)
        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    // ── Bug 4: multi-statement throw-only body should classify as stub ──────

    @Test
    fun `Bug 4 should classify multi-statement throw-only body as stub`() {
        // MSSQL pattern: logging + MessageFormat setup + throw SQLServerException
        val method = parseMethod("""
            public final java.sql.ResultSet executeQuery(String sql) {
                if (loggerExternal.isLoggable(Level.FINER)) {
                    loggerExternal.entering("Class", "executeQuery", sql);
                }
                MessageFormat form = new MessageFormat("R_cannotTakeArgumentsPreparedOrCallable");
                Object[] msgArgs = {"executeQuery()"};
                throw new SQLServerException(this, form.format(msgArgs), null, 0, false);
            }
        """)
        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.ThrowsSqlException)
    }

    @Test
    fun `Bug 4 should classify setClientInfo-style stub as stub not Partial`() {
        // CUBRID pattern: build exception, attach cause, throw it
        val method = parseMethod("""
            public void setClientInfo(java.util.Properties arg0) {
                SQLClientInfoException clientEx = new SQLClientInfoException();
                clientEx.initCause(new java.lang.UnsupportedOperationException());
                throw clientEx;
            }
        """)
        val status = detector.analyzeMethodBody(method)
        // initCause(new UnsupportedOperationException()) is setup; final throw is
        // a generic exception → classify as stub (ThrowsUnsupported via fallback).
        assertThat(status).isIn(
            ImplementationStatus.ThrowsUnsupported,
            ImplementationStatus.ThrowsSqlException,
        )
    }

    @Test
    fun `Bug 4 should still classify mixed-logic method as Partial`() {
        // Real partial: actual logic + a single throw branch
        val method = parseMethod("""
            public Statement createStatement(int type, int concur, int hold) {
                checkIsOpen();
                if (hold == 1) {
                    if (type == 2 || concur == 4) {
                        throw new SQLException(new java.lang.UnsupportedOperationException());
                    }
                }
                Statement stmt = new Stmt(type, concur, hold);
                addStatement(stmt);
                return stmt;
            }
        """)
        val status = detector.analyzeMethodBody(method)
        assertThat(status).isEqualTo(ImplementationStatus.Partial)
    }

    // ── Bug 8: validation + literal-return is effectively a stub ────────────

    @Test
    fun `Bug 8 should classify checkIsOpen then return empty string as ReturnsDefault`() {
        // CUBRID Connection.getCatalog() pattern
        val method = parseMethod("""
            public String getCatalog() throws SQLException {
                checkIsOpen();
                return "";
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 8 should classify checkIsOpen then return null as ReturnsDefault`() {
        // CUBRID Connection.getWarnings() / Statement.getWarnings() pattern
        val method = parseMethod("""
            public SQLWarning getWarnings() throws SQLException {
                checkIsOpen();
                return null;
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 8 should classify checkIsOpen then return false as ReturnsDefault`() {
        // CUBRID DatabaseMetaData.allProceduresAreCallable() / isReadOnly() pattern
        val method = parseMethod("""
            public boolean allProceduresAreCallable() throws SQLException {
                checkIsOpen();
                return false;
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 8 should classify validation plus logging plus return as ReturnsDefault`() {
        // 3-statement body still within the size<=3 bound
        val method = parseMethod("""
            public String getCursorName() throws SQLException {
                checkIsOpen();
                log.debug("getCursorName called");
                return "";
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 8 must NOT misclassify real implementation as ReturnsDefault`() {
        // Has actual logic between validation and return — must stay FullyImplemented
        val method = parseMethod("""
            public String getCatalog() throws SQLException {
                checkIsOpen();
                String name = con.queryCatalog();
                return name;
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.FullyImplemented)
    }

    @Test
    fun `Bug 8 must NOT misclassify method returning a method-call result`() {
        // Validation + delegation should NOT be ReturnsDefault — it's a real call
        val method = parseMethod("""
            public int getHoldability() throws SQLException {
                checkIsOpen();
                return connection.getHoldability();
            }
        """)
        // Returns a non-literal expression → not ReturnsDefault. Falls to FullyImplemented.
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.FullyImplemented)
    }

    @Test
    fun `Bug 8 should keep void no-op validation method as ReturnsDefault (regression)`() {
        // Pre-existing void+validation case must still work
        val method = parseMethod("""
            public void setReadOnly(boolean readOnly) throws SQLException {
                checkIsOpen();
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    // ── Bug 9: ANY literal return after validation is also a stub ───────────

    @Test
    fun `Bug 9 should classify checkIsOpen then return true as ReturnsDefault`() {
        // CUBRID DatabaseMetaData.supportsCorrelatedSubqueries pattern
        val method = parseMethod("""
            public boolean supportsCorrelatedSubqueries() throws SQLException {
                checkIsOpen();
                return true;
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 9 should classify checkIsOpen then return non-zero int as ReturnsDefault`() {
        val method = parseMethod("""
            public int getMaxBinaryLiteralLength() throws SQLException {
                checkIsOpen();
                return 1073741823;
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 9 should classify checkIsOpen then return non-empty string as ReturnsDefault`() {
        val method = parseMethod("""
            public String getDatabaseProductName() throws SQLException {
                checkIsOpen();
                return "CUBRID";
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.ReturnsDefault)
    }

    @Test
    fun `Bug 9 must NOT misclassify return-method-call as ReturnsDefault`() {
        // last stmt is a method call (not literal) → should be FullyImplemented
        val method = parseMethod("""
            public int getHoldability() throws SQLException {
                checkIsOpen();
                return connection.getHoldability();
            }
        """)
        assertThat(detector.analyzeMethodBody(method)).isEqualTo(ImplementationStatus.FullyImplemented)
    }
}
