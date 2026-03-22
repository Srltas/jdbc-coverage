package com.jdbcchecker.detector

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.MethodSignature

/**
 * Detects the implementation level of JDBC methods in driver source code.
 *
 * Analyzes method bodies to determine if they are:
 * - Not found (method doesn't exist)
 * - Stubs (throw UnsupportedOperationException, return default, etc.)
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
        val method = findMatchingMethod(specMethod, classDecl)
            ?: return ImplementationStatus.NotFound

        return analyzeMethodBody(method)
    }

    /**
     * Find a method in the class that matches the spec method signature.
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
     * Uses simple name matching first, falls back to resolved type matching.
     */
    private fun matchesParameterTypes(
        method: MethodDeclaration,
        specTypes: List<String>,
    ): Boolean {
        return method.parameters.zip(specTypes).all { (param, specType) ->
            val paramType = param.type.asString()
            paramType == specType || paramType.endsWith(".$specType") || specType.endsWith(".$paramType")
        }
    }

    /**
     * Analyze the body of a method to determine implementation level.
     */
    internal fun analyzeMethodBody(method: MethodDeclaration): ImplementationStatus {
        val body = method.body.orElse(null)
            ?: return ImplementationStatus.NotFound

        val statements = body.statements
        if (statements.isEmpty()) {
            return ImplementationStatus.ReturnsDefault
        }

        // Single statement analysis
        if (statements.size == 1) {
            val stmt = statements[0]

            // Check: throw new UnsupportedOperationException(...)
            if (stmt.isThrowStmt) {
                val throwExpr = stmt.asThrowStmt().expression.toString()
                return when {
                    "UnsupportedOperationException" in throwExpr -> ImplementationStatus.ThrowsUnsupported
                    "SQLException" in throwExpr -> ImplementationStatus.ThrowsSqlException
                    else -> ImplementationStatus.ThrowsUnsupported
                }
            }

            // Check: return null / return 0 / return false
            if (stmt.isReturnStmt) {
                val expr = stmt.asReturnStmt().expression.orElse(null)
                if (expr == null || isDefaultValue(expr)) {
                    return ImplementationStatus.ReturnsDefault
                }
                // Single return of a method call → likely delegation
                if (expr.isMethodCallExpr) {
                    return ImplementationStatus.Delegates
                }
            }
        }

        // Multi-statement: check if any branch throws "not supported"
        val hasUnsupportedThrow = statements.any { stmt ->
            stmt.toString().let {
                "UnsupportedOperationException" in it ||
                    ("SQLException" in it && "not supported" in it.lowercase())
            }
        }

        return if (hasUnsupportedThrow) {
            ImplementationStatus.Partial
        } else {
            ImplementationStatus.FullyImplemented
        }
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
