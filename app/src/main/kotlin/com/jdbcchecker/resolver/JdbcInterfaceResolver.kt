package com.jdbcchecker.resolver

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.resolution.types.ResolvedReferenceType

/**
 * Resolves which classes in the source implement JDBC interfaces.
 *
 * Selection strategy (in priority order):
 * 1. Class that directly declares "implements <JdbcInterface>"
 * 2. If multiple direct implementors exist, prefer the one whose name
 *    doesn't contain Wrapper/XA/Pooling/Out (heuristic for main impl)
 * 3. If no direct implementor, fall back to the class with the most
 *    methods (likely the richest implementation)
 *
 * Example (CUBRID):
 *   CUBRIDConnection implements Connection           → selected for Connection
 *   CUBRIDConnectionWrapperXA extends CUBRIDConnection → skipped (wrapper)
 *   CUBRIDStatement implements Statement              → selected for Statement
 *   CUBRIDPreparedStatement extends CUBRIDStatement implements PreparedStatement
 *     → selected for PreparedStatement, NOT for Statement
 */
class JdbcInterfaceResolver {

    /**
     * Resolve JDBC interface → implementing class mapping.
     */
    fun resolve(compilationUnits: List<CompilationUnit>): Map<String, ClassOrInterfaceDeclaration> {
        val allClasses = compilationUnits.flatMap { cu ->
            cu.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { !it.isInterface }
        }

        val jdbcImplementors = findJdbcImplementors(allClasses)
        return selectBestImplementor(jdbcImplementors)
    }

    /**
     * Find all classes that implement any JDBC interface (directly or transitively).
     * Tracks whether each class directly declares "implements <interface>".
     */
    private fun findJdbcImplementors(
        classes: List<ClassOrInterfaceDeclaration>,
    ): Map<String, List<ImplementorInfo>> {
        val result = mutableMapOf<String, MutableList<ImplementorInfo>>()

        for (classDecl in classes) {
            val directInterfaces = resolveDirectJdbcInterfaces(classDecl)
            val allInterfaces = resolveAllJdbcInterfaces(classDecl)

            for (iface in allInterfaces) {
                val isDirect = iface in directInterfaces
                result.getOrPut(iface) { mutableListOf() }
                    .add(ImplementorInfo(classDecl, isDirect))
            }
        }

        return result
    }

    /**
     * Get JDBC interfaces this class directly declares in its "implements" clause.
     */
    private fun resolveDirectJdbcInterfaces(classDecl: ClassOrInterfaceDeclaration): Set<String> {
        val directNames = classDecl.implementedTypes.map { it.nameAsString }.toSet()

        // Try to resolve to fully qualified names
        return classDecl.implementedTypes.mapNotNull { type ->
            try {
                val resolved = type.resolve()
                if (resolved.isReferenceType) {
                    resolved.asReferenceType().qualifiedName
                } else {
                    null
                }
            } catch (e: Exception) {
                // Fallback: map simple name to FQN
                val simpleName = type.nameAsString
                SIMPLE_TO_FQN[simpleName]
            }
        }.filter { name ->
            name.startsWith("java.sql.") || name.startsWith("javax.sql.")
        }.toSet()
    }

    /**
     * Get all JDBC interfaces a class implements (directly or via parent).
     */
    private fun resolveAllJdbcInterfaces(classDecl: ClassOrInterfaceDeclaration): Set<String> {
        return try {
            val resolved = classDecl.resolve()
            val allAncestors: List<ResolvedReferenceType> = resolved.getAllAncestors()
            allAncestors.map { ancestor ->
                ancestor.qualifiedName
            }.filter { name ->
                name.startsWith("java.sql.") || name.startsWith("javax.sql.")
            }.toSet()
        } catch (e: Exception) {
            // Fallback: use direct implements only
            resolveDirectJdbcInterfaces(classDecl)
        }
    }

    /**
     * Select the best implementing class for each JDBC interface.
     *
     * Priority:
     * 1. Direct implementor (declares "implements <Interface>")
     * 2. Among direct implementors, prefer "clean" names (no Wrapper/XA/Pooling/Out)
     * 3. If tie, prefer the class with more methods
     * 4. If no direct implementor, select class with most methods
     */
    private fun selectBestImplementor(
        implementors: Map<String, List<ImplementorInfo>>,
    ): Map<String, ClassOrInterfaceDeclaration> {
        return implementors.mapValues { (_, infos) ->
            if (infos.size == 1) {
                infos.first().classDecl
            } else {
                // Prefer direct implementors
                val direct = infos.filter { it.isDirect }
                val candidates = direct.ifEmpty { infos }

                // Among candidates, prefer "clean" class names
                val clean = candidates.filter { isMainImplClass(it.classDecl) }
                val finalCandidates = clean.ifEmpty { candidates }

                // Select the one with most methods (richest implementation)
                finalCandidates.maxByOrNull { it.classDecl.methods.size }?.classDecl
                    ?: infos.first().classDecl
            }
        }
    }

    /**
     * Heuristic: a class is likely the "main" implementation if its name
     * doesn't contain wrapper/adapter/proxy patterns.
     */
    private fun isMainImplClass(classDecl: ClassOrInterfaceDeclaration): Boolean {
        val name = classDecl.nameAsString.lowercase()
        return WRAPPER_PATTERNS.none { it in name }
    }

    private data class ImplementorInfo(
        val classDecl: ClassOrInterfaceDeclaration,
        val isDirect: Boolean,
    )

    companion object {
        private val JDBC_INTERFACE_SIMPLE_NAMES = setOf(
            "Connection", "Statement", "PreparedStatement", "CallableStatement",
            "ResultSet", "DatabaseMetaData", "ResultSetMetaData", "ParameterMetaData",
            "Driver", "Blob", "Clob", "NClob", "SQLXML", "Array", "Struct", "Ref",
            "Wrapper", "DataSource", "ConnectionPoolDataSource", "CommonDataSource",
            "PooledConnection", "XAConnection", "XADataSource",
        )

        /** Simple name → fully qualified name mapping for fallback resolution */
        private val SIMPLE_TO_FQN = mapOf(
            "Connection" to "java.sql.Connection",
            "Statement" to "java.sql.Statement",
            "PreparedStatement" to "java.sql.PreparedStatement",
            "CallableStatement" to "java.sql.CallableStatement",
            "ResultSet" to "java.sql.ResultSet",
            "DatabaseMetaData" to "java.sql.DatabaseMetaData",
            "ResultSetMetaData" to "java.sql.ResultSetMetaData",
            "ParameterMetaData" to "java.sql.ParameterMetaData",
            "Driver" to "java.sql.Driver",
            "Blob" to "java.sql.Blob",
            "Clob" to "java.sql.Clob",
            "NClob" to "java.sql.NClob",
            "SQLXML" to "java.sql.SQLXML",
            "Array" to "java.sql.Array",
            "Struct" to "java.sql.Struct",
            "Ref" to "java.sql.Ref",
            "Wrapper" to "java.sql.Wrapper",
            "DataSource" to "javax.sql.DataSource",
            "ConnectionPoolDataSource" to "javax.sql.ConnectionPoolDataSource",
            "CommonDataSource" to "javax.sql.CommonDataSource",
        )

        /** Patterns indicating wrapper/adapter/proxy classes (not main impl) */
        private val WRAPPER_PATTERNS = listOf(
            "wrapper", "xa", "pooling", "proxy", "adapter", "delegate",
        )
    }
}
