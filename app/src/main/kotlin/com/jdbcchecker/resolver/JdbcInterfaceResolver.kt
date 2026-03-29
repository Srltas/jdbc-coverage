package com.jdbcchecker.resolver

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.resolution.types.ResolvedReferenceType

/**
 * Resolves which classes in the source implement JDBC interfaces.
 *
 * Selection strategy (in priority order):
 * 1. Concrete (non-abstract) top-level classes are preferred over abstract classes.
 *    If only abstract classes implement an interface, they are used as a fallback.
 * 2. Direct implementor (declares "implements <JdbcInterface>") is preferred over
 *    transitive implementors.
 * 3. Among equally-ranked candidates, "clean" class names are preferred
 *    (no Wrapper/Pooling/Proxy/Adapter/Delegate patterns).
 * 4. Among remaining ties, the class with the most methods wins
 *    (richest implementation heuristic).
 *
 * Inner/nested classes are excluded from candidate selection entirely because
 * they are not standalone, instantiable JDBC implementations.
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
     *
     * @param compilationUnits parsed source files
     * @param overrides explicit mapping of JDBC interface FQN → implementing class FQN.
     *   Overrides auto-detection for the specified interfaces.
     *   Example: `"java.sql.Connection" to "com.mysql.cj.jdbc.ConnectionImpl"`
     */
    fun resolve(
        compilationUnits: List<CompilationUnit>,
        overrides: Map<String, String> = emptyMap(),
    ): Map<String, ClassOrInterfaceDeclaration> {
        val allClasses = compilationUnits.flatMap { cu ->
            cu.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { decl ->
                    !decl.isInterface &&
                        !decl.isNestedType // exclude inner/nested classes
                }
        }

        val jdbcImplementors = findJdbcImplementors(allClasses)
        val result = selectBestImplementor(jdbcImplementors).toMutableMap()

        // Apply explicit overrides: bypass auto-detection for specified interfaces
        for ((jdbcInterface, classFqcn) in overrides) {
            val classDecl = findClassByFqcn(allClasses, classFqcn)
            if (classDecl != null) {
                result[jdbcInterface] = classDecl
            } else {
                System.err.println("Warning: Entry class '$classFqcn' not found in parsed sources (for $jdbcInterface)")
            }
        }

        return result
    }

    /**
     * Find a class declaration by its fully qualified name.
     */
    private fun findClassByFqcn(
        classes: List<ClassOrInterfaceDeclaration>,
        fqcn: String,
    ): ClassOrInterfaceDeclaration? {
        val simpleName = fqcn.substringAfterLast('.')
        val packageName = fqcn.substringBeforeLast('.', "")
        return classes.firstOrNull { decl ->
            decl.nameAsString == simpleName &&
                decl.findCompilationUnit()
                    .flatMap { it.packageDeclaration }
                    .map { it.nameAsString }
                    .orElse("") == packageName
        }
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
            isJdbcInterface(name)
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
                isJdbcInterface(name)
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
     * 1. Concrete (non-abstract) classes preferred over abstract classes.
     *    Abstract classes are used as fallback when no concrete implementor exists.
     * 2. Direct implementor (declares "implements <Interface>") preferred over
     *    transitive (via parent chain).
     * 3. Among equally-ranked candidates, "clean" class names preferred
     *    (no Wrapper/Pooling/Proxy/Adapter/Delegate).
     * 4. Among remaining ties, class with most methods (richest implementation).
     */
    private fun selectBestImplementor(
        implementors: Map<String, List<ImplementorInfo>>,
    ): Map<String, ClassOrInterfaceDeclaration> {
        return implementors.mapValues { (_, infos) ->
            if (infos.size == 1) {
                infos.first().classDecl
            } else {
                // Priority 1: prefer concrete over abstract
                val concrete = infos.filter { !it.classDecl.isAbstract }
                val afterAbstractFilter = concrete.ifEmpty { infos }

                // Priority 2: prefer direct implementors
                val direct = afterAbstractFilter.filter { it.isDirect }
                val candidates = direct.ifEmpty { afterAbstractFilter }

                // Priority 3: prefer "clean" class names
                val clean = candidates.filter { isMainImplClass(it.classDecl) }
                val finalCandidates = clean.ifEmpty { candidates }

                // Priority 4: richest implementation
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
            // java.sql
            "Connection", "Statement", "PreparedStatement", "CallableStatement",
            "ResultSet", "DatabaseMetaData", "ResultSetMetaData", "ParameterMetaData",
            "Driver", "Blob", "Clob", "NClob", "SQLXML", "Array", "Struct", "Ref",
            "Wrapper",
            // javax.sql
            "DataSource", "ConnectionPoolDataSource", "CommonDataSource",
            "PooledConnection", "PooledConnectionBuilder",
            "XAConnection", "XAConnectionBuilder", "XADataSource",
            // javax.transaction.xa
            "XAResource", "Xid",
        )

        /** Simple name → fully qualified name mapping for fallback resolution */
        private val SIMPLE_TO_FQN = mapOf(
            // java.sql
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
            // javax.sql
            "DataSource" to "javax.sql.DataSource",
            "ConnectionPoolDataSource" to "javax.sql.ConnectionPoolDataSource",
            "CommonDataSource" to "javax.sql.CommonDataSource",
            "PooledConnection" to "javax.sql.PooledConnection",
            "PooledConnectionBuilder" to "javax.sql.PooledConnectionBuilder",
            "XAConnection" to "javax.sql.XAConnection",
            "XAConnectionBuilder" to "javax.sql.XAConnectionBuilder",
            "XADataSource" to "javax.sql.XADataSource",
            // javax.transaction.xa
            "XAResource" to "javax.transaction.xa.XAResource",
            "Xid" to "javax.transaction.xa.Xid",
        )

        /** Returns true if the FQN belongs to a tracked JDBC/JTA package. */
        fun isJdbcInterface(fqn: String): Boolean =
            fqn.startsWith("java.sql.") ||
                fqn.startsWith("javax.sql.") ||
                fqn.startsWith("javax.transaction.xa.")

        /** Patterns indicating wrapper/adapter/proxy classes (not main impl).
         *  Note: "xa" is intentionally excluded — XA implementation classes
         *  (e.g., MysqlXAResource, CUBRIDXAResource) are valid main implementations. */
        private val WRAPPER_PATTERNS = listOf(
            "wrapper", "pooling", "proxy", "adapter", "delegate",
        )
    }
}
