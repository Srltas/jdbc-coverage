package com.jdbccoverage.detector

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.jdbccoverage.model.ImplementationStatus
import com.jdbccoverage.model.MethodSignature
import com.jdbccoverage.profile.ClassifyAs
import com.jdbccoverage.profile.StubHelper

/**
 * Detects the implementation level of JDBC methods in driver source code.
 *
 * Analyzes method bodies to determine if they are:
 * - Not found (method doesn't exist)
 * - Stubs (throw UnsupportedOperationException/SQLFeatureNotSupportedException,
 *   return default, etc.)
 * - Implemented (actual logic present)
 *
 * Driver profiles can extend the detector's recognition via two hooks:
 *
 * @param stubHelpers driver-specific helper-method calls that themselves
 *     throw an exception (e.g., MSSQL's
 *     `SQLServerException.throwNotSupportedException`). When a method body's
 *     terminal statement is a call matching one of these patterns, the
 *     method is classified as a stub of the helper's declared type.
 *
 * @param extraStubExceptionClasses driver-specific exception class names
 *     that signal a stub when thrown (in addition to the built-in
 *     `STUB_EXCEPTION_NAME_REGEX` defaults).
 */
class ImplementationDetector(
    private val stubHelpers: List<StubHelper> = emptyList(),
    private val extraStubExceptionClasses: List<String> = emptyList(),
) {

    /**
     * Detect implementation status for a spec method in the given class.
     * Searches the class and its parent classes in the source.
     */
    fun detect(
        specMethod: MethodSignature,
        classDecl: ClassOrInterfaceDeclaration,
    ): ImplementationStatus {
        // Search in the class itself
        val method = findMatchingMethod(specMethod, classDecl)
        if (method != null) {
            return analyzeMethodBody(method)
        }

        // Search in parent classes within the same compilation unit set
        val parentResult = searchParentClasses(specMethod, classDecl)
        if (parentResult != null) {
            return parentResult
        }

        return ImplementationStatus.NotFound
    }

    /**
     * Walk up the inheritance chain looking for the method in parent classes.
     */
    private fun searchParentClasses(
        specMethod: MethodSignature,
        classDecl: ClassOrInterfaceDeclaration,
    ): ImplementationStatus? {
        val parentTypeName = classDecl.extendedTypes.firstOrNull()?.nameAsString
            ?: return null

        val parentClass = findClassByName(parentTypeName, classDecl)
            ?: return null

        val method = findMatchingMethod(specMethod, parentClass)
        if (method != null) {
            return analyzeMethodBody(method)
        }

        // Continue up the chain recursively
        return searchParentClasses(specMethod, parentClass)
    }

    /**
     * Find a class declaration by simple name.
     *
     * Resolution order:
     * 1. Same compilation unit (fastest, most specific)
     * 2. Registry: same-package classes preferred over other-package classes
     *    (prevents simple-name collisions across packages, e.g. mysql.cj.jdbc.Statement
     *    vs mysql.cj.xdevapi.Statement)
     */
    private fun findClassByName(
        simpleName: String,
        referenceClass: ClassOrInterfaceDeclaration,
    ): ClassOrInterfaceDeclaration? {
        // 1. Search in the same compilation unit first
        val cu = referenceClass.findCompilationUnit().orElse(null)
        cu?.findAll(ClassOrInterfaceDeclaration::class.java)
            ?.find { it.nameAsString == simpleName }
            ?.let { return it }

        // 2. Search in registered compilation units
        val candidates = parentClassRegistryBySimple[simpleName]
            ?: return null
        if (candidates.size == 1) return candidates.first()

        // Prefer class in the same package as the reference class
        val refPkg = cu
            ?.packageDeclaration
            ?.map { it.nameAsString }
            ?.orElse("") ?: ""
        return candidates.firstOrNull { decl ->
            decl.findCompilationUnit()
                .flatMap { it.packageDeclaration }
                .map { it.nameAsString }
                .orElse("") == refPkg
        } ?: candidates.first()
    }

    /**
     * Register all parsed compilation units so parent classes can be found
     * across files.
     *
     * Maintains two indices:
     * - FQN → ClassDecl  (used for exact lookups)
     * - simpleName → List<ClassDecl>  (used for parent-chain traversal where
     *   only the simple name is known from the `extends` clause)
     */
    fun registerCompilationUnits(compilationUnits: List<CompilationUnit>) {
        for (cu in compilationUnits) {
            cu.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { !it.isInterface }
                .forEach { classDecl ->
                    val fqn = classDecl.fullyQualifiedName.orElse(classDecl.nameAsString)
                    parentClassRegistryByFqn[fqn] = classDecl
                    parentClassRegistryBySimple
                        .getOrPut(classDecl.nameAsString) { mutableListOf() }
                        .add(classDecl)
                }
        }
    }

    // FQN → ClassDecl (for precise lookups when FQN is known)
    private val parentClassRegistryByFqn = mutableMapOf<String, ClassOrInterfaceDeclaration>()

    // simpleName → List<ClassDecl> (for parent-chain traversal; same-package preferred)
    private val parentClassRegistryBySimple =
        mutableMapOf<String, MutableList<ClassOrInterfaceDeclaration>>()

    /**
     * Find a method in the class that matches the spec method signature.
     * Parameter types are compared after stripping generic parameters so that
     * e.g. "Class<T>" in source matches "Class" in the spec YAML.
     */
    private fun findMatchingMethod(
        specMethod: MethodSignature,
        classDecl: ClassOrInterfaceDeclaration,
    ): MethodDeclaration? {
        return classDecl.methods.find { method ->
            method.nameAsString == specMethod.methodName &&
                method.parameters.size == specMethod.parameterTypes.size &&
                matchesParameterTypes(method, specMethod.parameterTypes)
        }
    }

    /**
     * Check if method parameter types match the spec.
     *
     * Matching strategy (all three comparisons applied after generic-stripping):
     * 1. Exact match after stripping generics: "Class<T>" → "Class" == "Class"
     * 2. FQN → simple name:  "java.lang.String" ends with ".String"
     * 3. Simple name → FQN:  "String" is contained in "java.lang.String"
     */
    private fun matchesParameterTypes(
        method: MethodDeclaration,
        specTypes: List<String>,
    ): Boolean {
        return method.parameters.zip(specTypes).all { (param, specType) ->
            val paramType = stripGenericParameters(param.type.asString())
            val normalizedSpec = stripGenericParameters(specType)
            paramType == normalizedSpec ||
                paramType.endsWith(".$normalizedSpec") ||
                normalizedSpec.endsWith(".$paramType")
        }
    }

    /**
     * Strip generic type parameters for matching purposes.
     * "Class<T>"           → "Class"
     * "Map<String,Class<?>>" → "Map"
     */
    private fun stripGenericParameters(type: String): String {
        val idx = type.indexOf('<')
        return if (idx >= 0) type.substring(0, idx).trim() else type
    }

    /**
     * Analyze the body of a method to determine implementation level.
     *
     * Classification rules (applied in order):
     *
     * 1. No body (abstract/interface)  → NotFound
     * 2. Empty body {}                 → ReturnsDefault
     *
     * Single-statement methods:
     * 3a. throw UnsupportedOperationException / SQLFeatureNotSupportedException → ThrowsUnsupported
     * 3b. throw SQLException (other)   → ThrowsSqlException
     * 3c. throw (other)                → ThrowsUnsupported
     * 3d. return null/0/false/""       → ReturnsDefault
     * 3e. return someMethod(...)       → Delegates
     *
     * Single try-statement (try { return x.method() } catch...):
     * 3f. try wrapping a single delegation → Delegates
     *
     * Multi-statement methods:
     * 4a. Throw-only body (setup/logging + final throw) → stub
     *     (classified as ThrowsUnsupported / ThrowsSqlException based on throw type)
     * 4b. Contains "not supported" throw pattern AND other real logic → Partial
     * 4c. void method with only validation/logging calls               → ReturnsDefault
     * 4d. otherwise                                                    → FullyImplemented
     */
    internal fun analyzeMethodBody(method: MethodDeclaration): ImplementationStatus {
        val body = method.body.orElse(null)
            ?: return ImplementationStatus.NotFound

        val statements = body.statements
        if (statements.isEmpty()) {
            return ImplementationStatus.ReturnsDefault
        }

        // ── Single-statement analysis ─────────────────────────────────────
        if (statements.size == 1) {
            val stmt = statements[0]

            // throw new UnsupportedOperationException / SQLFeatureNotSupportedException / etc.
            if (stmt.isThrowStmt) {
                return classifyThrow(stmt.asThrowStmt().expression.toString())
            }

            // return null / return 0 / return false / return someMethod()
            if (stmt.isReturnStmt) {
                val expr = stmt.asReturnStmt().expression.orElse(null)
                if (expr == null || isDefaultValue(expr)) {
                    return ImplementationStatus.ReturnsDefault
                }
                if (expr.isMethodCallExpr) {
                    return ImplementationStatus.Delegates
                }
            }

            // try { return x.method() } catch (...) { ... }
            // Treat as Delegates when the try-block contains a single delegation.
            if (stmt.isTryStmt) {
                val tryBodyStmts = stmt.asTryStmt().tryBlock.statements
                if (tryBodyStmts.size == 1 && tryBodyStmts[0].isReturnStmt) {
                    val innerExpr = tryBodyStmts[0].asReturnStmt().expression.orElse(null)
                    if (innerExpr != null && innerExpr.isMethodCallExpr) {
                        return ImplementationStatus.Delegates
                    }
                }
                // Fall through to multi-statement analysis for other try structures
            }
        }

        // ── Multi-statement analysis ──────────────────────────────────────
        // Bug 4: If body is "throw-only" (setup/logging + terminal throw with no real
        // logic path), classify as a stub by the throw type rather than Partial.
        // This catches MSSQL stubs like:
        //   if (logger...) logger.entering(...);
        //   MessageFormat form = new MessageFormat(...);
        //   Object[] msgArgs = { "method()" };
        //   throw new SQLServerException(this, form.format(msgArgs), null, 0, false);
        val throwOnlyClassification = classifyAsThrowOnlyIfApplicable(statements)
        if (throwOnlyClassification != null) {
            return throwOnlyClassification
        }

        val hasUnsupportedThrow = statements.any { stmt -> isUnsupportedThrow(stmt) }
        if (hasUnsupportedThrow) {
            return ImplementationStatus.Partial
        }

        // Bug 8: methods whose body is "validation/logging calls + (terminal
        // no-op)" are effectively stubs that return a placeholder value. Cover
        // both void methods (last stmt absent or any) and non-void methods
        // whose last stmt is `return <literal>`.
        //
        // Examples caught:
        //   public String getCatalog()                    // CUBRID
        //   { checkIsOpen(); return ""; }                  → ReturnsDefault
        //
        //   public boolean isReadOnly()                   // CUBRID
        //   { checkIsOpen(); return false; }              → ReturnsDefault
        //
        //   public void setSchema(String s)               // generic JDBC stub
        //   { checkOpen(); }                              → ReturnsDefault (void)
        //
        // Bounded to short bodies (<=3 statements) so that genuine logic isn't
        // mistakenly demoted.
        if (statements.size <= 3 && looksLikeNoopWithValidation(method, statements)) {
            return ImplementationStatus.ReturnsDefault
        }

        return ImplementationStatus.FullyImplemented
    }

    /**
     * Bug 1 + 4: classify a `throw <expr>` based on the thrown type's textual name.
     *
     * Recognised patterns (string-based, since type resolution may not be available
     * for driver-specific exception classes):
     * - UnsupportedOperationException             → ThrowsUnsupported
     * - SQLFeatureNotSupportedException           → ThrowsUnsupported  (JDBC standard)
     * - SQLException / *SQLException* (suffix)    → ThrowsSqlException
     *   (catches PSQLException, SQLServerException, SQLPrepareException, …)
     * - NotUpdatable / OperationNotSupportedException / *NotSupported* / *NotImplemented*
     *                                             → ThrowsSqlException (driver-specific)
     * - anything else                             → ThrowsUnsupported (default)
     */
    private fun classifyThrow(throwExpr: String): ImplementationStatus = when {
        "UnsupportedOperationException" in throwExpr -> ImplementationStatus.ThrowsUnsupported
        "SQLFeatureNotSupportedException" in throwExpr -> ImplementationStatus.ThrowsUnsupported
        // Match SQLException and any driver-specific subclass whose name ends with
        // "SQLException" (PSQLException, SQLServerException, BatchUpdateException-style, …).
        // We use a regex anchored on word boundary + a *SQLException* suffix.
        SQL_EXCEPTION_REGEX.containsMatchIn(throwExpr) -> ImplementationStatus.ThrowsSqlException
        // Driver-specific "not supported" / "not updatable" exception names that don't
        // contain "SQLException" textually but signal an unsupported operation.
        STUB_EXCEPTION_NAME_REGEX.containsMatchIn(throwExpr) -> ImplementationStatus.ThrowsSqlException
        // Profile-provided exception class names (driver-specific stubs that the
        // built-in patterns don't recognize).
        matchesExtraStubException(throwExpr) -> ImplementationStatus.ThrowsSqlException
        else -> ImplementationStatus.ThrowsUnsupported
    }

    /** True if `text` mentions any profile-provided stub-exception class name. */
    private fun matchesExtraStubException(text: String): Boolean {
        if (extraStubExceptionClasses.isEmpty()) return false
        return extraStubExceptionClasses.any { className ->
            // Word-boundary check so "Foo" doesn't match "FooBar"
            Regex("\\b" + Regex.escape(className) + "\\b").containsMatchIn(text)
        }
    }

    /**
     * Bug 4: detect "throw-only" multi-statement methods — the body's final
     * statement is a throw and there is no `return` anywhere reachable, so all
     * paths end in throwing. Returns the appropriate stub status (by throw
     * type) when applicable, otherwise null.
     *
     * Examples matched:
     *   MSSQL stub:
     *     if (loggerExternal.isLoggable(FINER)) loggerExternal.entering(...);
     *     MessageFormat form = new MessageFormat(...);
     *     Object[] msgArgs = {"executeQuery()"};
     *     throw new SQLServerException(this, form.format(msgArgs), null, 0, false);
     *
     *   CUBRID stub:
     *     SQLClientInfoException clientEx = new SQLClientInfoException();
     *     clientEx.initCause(new UnsupportedOperationException());
     *     throw clientEx;
     *
     * Examples NOT matched (fall through to Partial / FullyImplemented):
     *   - try { … return x; } catch { throw … }   — body's last stmt is try, not throw
     *   - if (x) throw …; return real;            — has a return path
     */
    private fun classifyAsThrowOnlyIfApplicable(
        statements: List<Statement>,
    ): ImplementationStatus? {
        val last = statements.lastOrNull() ?: return null
        // If any statement (anywhere, including nested) returns a value, the
        // method has at least one non-throwing path → not a pure stub.
        val hasReturn = statements.any { it.findAll(ReturnStmt::class.java).isNotEmpty() }
        if (hasReturn) return null

        // (a) Direct `throw …` statement at the end
        if (last.isThrowStmt) {
            return classifyThrow(last.asThrowStmt().expression.toString())
        }

        // (b) Driver-specific stub helper call at the end (e.g. MSSQL's
        //     SQLServerException.throwNotSupportedException(con, this);). The
        //     helper itself throws but the keyword `throw` is absent.
        val helperMatch = matchStubHelper(last)
        if (helperMatch != null) {
            return when (helperMatch.classify) {
                ClassifyAs.THROWS_UNSUPPORTED -> ImplementationStatus.ThrowsUnsupported
                ClassifyAs.THROWS_SQL_EXCEPTION -> ImplementationStatus.ThrowsSqlException
            }
        }

        return null
    }

    /**
     * Returns the first [StubHelper] whose `callPattern` textually appears in
     * the given statement, or null when no profile helper matches. Used to
     * detect driver-specific "throwing helper" method calls.
     */
    private fun matchStubHelper(stmt: Statement): StubHelper? {
        if (stubHelpers.isEmpty()) return null
        val text = stmt.toString()
        return stubHelpers.firstOrNull { text.contains(it.callPattern) }
    }

    /**
     * Returns true if the statement (or any nested statement) throws an
     * "operation not supported" exception.
     *
     * Recognised patterns:
     * - UnsupportedOperationException (any context)
     * - SQLFeatureNotSupportedException (any context)
     * - SQLException (or subclass) with "not supported" in the message text
     * - Driver-specific names like NotUpdatable / OperationNotSupportedException
     */
    private fun isUnsupportedThrow(stmt: Statement): Boolean {
        val text = stmt.toString()
        if ("UnsupportedOperationException" in text) return true
        if ("SQLFeatureNotSupportedException" in text) return true
        // SQLException or *SQLException* subclass throw with "not supported" message
        if (SQL_EXCEPTION_REGEX.containsMatchIn(text) &&
            ("not supported" in text.lowercase() || "notsupported" in text.lowercase())
        ) {
            return true
        }
        // Driver-specific stub-style exception names (built-in defaults)
        if (STUB_EXCEPTION_NAME_REGEX.containsMatchIn(text)) return true
        // Driver-specific stub-style exception names supplied by the active profile
        if (matchesExtraStubException(text)) return true
        return false
    }

    private companion object {
        /**
         * Matches exception class names in the broader "SQL-related" family.
         * The pattern is "any prefix" + "SQL" + "any suffix" + "Exception".
         *
         * Matches (some examples):
         *   SQLException           → "" + SQL + "" + Exception
         *   PSQLException          → "P" + SQL + "" + Exception
         *   SQLServerException     → "" + SQL + "Server" + Exception
         *   SQLClientInfoException → "" + SQL + "ClientInfo" + Exception
         *   SQLPrepareException    → "" + SQL + "Prepare" + Exception
         *   MySQLException         → "My" + SQL + "" + Exception
         *   SQLNonTransientConnectionException, SQLRecoverableException, …
         *
         * This catches both java.sql.SQLException and any driver-specific subclass
         * with "SQL" in its name (Microsoft SQLServerException, PostgreSQL
         * PSQLException, MariaDB SQLPrepareException, MySQL's *SQLException, …).
         */
        val SQL_EXCEPTION_REGEX = Regex("""\b[A-Za-z0-9_]*SQL[A-Za-z0-9_]*Exception\b""")

        /**
         * Driver-specific exception names that signal "unsupported / cannot do
         * this" but don't contain "SQL" textually. These are functionally stubs.
         *
         * Examples:
         *   NotUpdatable (MySQL — used inside ResultSetImpl.updateXxx stubs)
         *   OperationNotSupportedException (MySQL)
         *   NotImplementedException / NotSupportedException (common 3rd-party)
         */
        val STUB_EXCEPTION_NAME_REGEX = Regex(
            """\b(?:NotUpdatable|OperationNotSupportedException|NotImplementedException|NotSupportedException)\b""",
        )
    }

    /**
     * Bug 8 + Bug 9: returns true when a method body is "validation/logging
     * calls only, with an optional terminal literal return". Such bodies
     * are functionally no-ops — the method has no real logic, just a
     * hardcoded answer.
     *
     * Bug 9 update: `return true`, `return 1`, `return any literal` are
     * also recognised (not only the default-value literals). Pattern like
     *   public boolean supportsX() {
     *       checkIsOpen();
     *       return true;     // hardcoded answer, no real logic
     *   }
     * is functionally identical to `return false` and should be classified
     * the same way (RETURNS_DEFAULT). Previously, the rule looked only at
     * "default" literals (false/null/0/"") which left ~250 supports*
     * methods misclassified as FullyImplemented.
     *
     * Recognised shapes (all classified as ReturnsDefault):
     *   - void method,  all stmts are validation/logging
     *   - non-void method, preceding stmts are validation/logging AND
     *     last stmt is `return <literal>`
     *   - empty body — already handled before this point
     */
    private fun looksLikeNoopWithValidation(
        method: MethodDeclaration,
        statements: List<Statement>,
    ): Boolean {
        if (statements.isEmpty()) return false
        val last = statements.last()
        val preceding = statements.dropLast(1)

        // Last statement must be either a no-op terminator or itself validation/logging.
        val lastIsNoopReturn = last.isReturnStmt && run {
            val expr = last.asReturnStmt().expression.orElse(null)
            expr == null || isLiteralExpression(expr)
        }
        val terminalOk = when {
            method.type.isVoidType -> isValidationOrLoggingCall(last) || lastIsNoopReturn
            lastIsNoopReturn -> true
            else -> false
        }
        if (!terminalOk) return false

        return preceding.all { isValidationOrLoggingCall(it) }
    }

    /**
     * Heuristic: returns true when a statement is nothing more than a call to a
     * validation helper or a logging method, e.g.:
     *   checkOpen();  checkClosed();  logger.debug("...");  log.trace("...");
     *
     * Used to detect void methods that override but do nothing meaningful:
     *   public void setSchema(String s) { checkOpen(); }
     */
    private fun isValidationOrLoggingCall(stmt: Statement): Boolean {
        if (!stmt.isExpressionStmt) return false
        val expr = stmt.asExpressionStmt().expression
        if (!expr.isMethodCallExpr) return false
        val name = expr.asMethodCallExpr().nameAsString.lowercase()
        return name.startsWith("check") ||
            name.startsWith("assert") ||
            name.startsWith("verify") ||
            name.startsWith("log") ||
            name.startsWith("trace") ||
            name.startsWith("debug") ||
            name.startsWith("warn") ||
            name in setOf("info", "fine", "finer", "finest", "entering", "exiting", "severe")
    }

    private fun isDefaultValue(expr: Expression): Boolean = when {
        expr.isNullLiteralExpr -> true
        expr.isBooleanLiteralExpr -> !expr.asBooleanLiteralExpr().value
        expr.isIntegerLiteralExpr -> expr.asIntegerLiteralExpr().value == "0"
        expr.isLongLiteralExpr -> expr.asLongLiteralExpr().value in listOf("0L", "0l", "0")
        expr.isDoubleLiteralExpr -> expr.asDoubleLiteralExpr().value in listOf("0.0", "0.0d", "0.0D")
        expr.isStringLiteralExpr -> expr.asStringLiteralExpr().value.isEmpty()
        else -> false
    }

    /**
     * Bug 9: returns true for ANY literal expression (true, false, null,
     * any int/long/double/string literal). Used by
     * [looksLikeNoopWithValidation] — when a method's last statement is
     * `return <literal>`, the method is effectively a no-op stub
     * regardless of whether the literal happens to be the "default" value
     * for its type. Examples: `return true` (hardcoded supports*),
     * `return 1` (constant version), `return "CUBRID"` (constant name).
     */
    private fun isLiteralExpression(expr: Expression): Boolean = when {
        expr.isNullLiteralExpr -> true
        expr.isBooleanLiteralExpr -> true   // both true and false count
        expr.isIntegerLiteralExpr -> true
        expr.isLongLiteralExpr -> true
        expr.isDoubleLiteralExpr -> true
        expr.isStringLiteralExpr -> true
        expr.isCharLiteralExpr -> true
        else -> false
    }
}
