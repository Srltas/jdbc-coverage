package com.jdbcchecker.detector

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.stmt.Statement
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.MethodSignature

/**
 * Detects the implementation level of JDBC methods in driver source code.
 *
 * Analyzes method bodies to determine if they are:
 * - Not found (method doesn't exist)
 * - Stubs (throw UnsupportedOperationException/SQLFeatureNotSupportedException,
 *   return default, etc.)
 * - Implemented (actual logic present)
 */
class ImplementationDetector {

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
     * 4a. Contains "not supported" throw pattern anywhere → Partial
     * 4b. void method with only validation/logging calls  → ReturnsDefault
     * 4c. otherwise                   → FullyImplemented
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
                val throwExpr = stmt.asThrowStmt().expression.toString()
                return when {
                    "UnsupportedOperationException" in throwExpr -> ImplementationStatus.ThrowsUnsupported
                    "SQLFeatureNotSupportedException" in throwExpr -> ImplementationStatus.ThrowsUnsupported
                    "SQLException" in throwExpr -> ImplementationStatus.ThrowsSqlException
                    else -> ImplementationStatus.ThrowsUnsupported
                }
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
        val hasUnsupportedThrow = statements.any { stmt -> isUnsupportedThrow(stmt) }

        if (hasUnsupportedThrow) {
            return ImplementationStatus.Partial
        }

        // Void methods consisting solely of validation/logging calls are effectively
        // no-ops and should not be counted as "fully implemented".
        if (method.type.isVoidType &&
            statements.size <= 2 &&
            statements.all { isValidationOrLoggingCall(it) }
        ) {
            return ImplementationStatus.ReturnsDefault
        }

        return ImplementationStatus.FullyImplemented
    }

    /**
     * Returns true if the statement (or any nested statement) throws an
     * "operation not supported" exception.
     *
     * Recognised patterns:
     * - UnsupportedOperationException (any context)
     * - SQLFeatureNotSupportedException (any context)
     * - SQLException with "not supported" in the message
     */
    private fun isUnsupportedThrow(stmt: Statement): Boolean {
        val text = stmt.toString()
        return "UnsupportedOperationException" in text ||
            "SQLFeatureNotSupportedException" in text ||
            ("SQLException" in text && "not supported" in text.lowercase())
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
}
