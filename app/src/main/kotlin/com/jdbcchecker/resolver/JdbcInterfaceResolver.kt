package com.jdbcchecker.resolver

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.resolution.types.ResolvedReferenceType

/**
 * Resolves which classes in the source implement JDBC interfaces.
 *
 * Selection strategy (in priority order):
 * 1. Concrete (non-abstract) classes preferred over abstract.
 * 2. Top-level classes preferred over nested/inner classes (Bug 2: but inner
 *    classes are still considered when no top-level candidate exists, e.g.
 *    MariaDB's `MariaDbXAPinnedResource` inner class is the only XAResource impl).
 * 3. "Clean" class names preferred over wrapper/proxy/adapter/delegate names
 *    (Bug 5: this is intentionally ahead of "direct implementor" so a literal
 *    Wrapper class can't outrank a non-wrapper that only implements the
 *    interface transitively via a driver-specific extension interface).
 * 4. Direct implementor (declares "implements <JdbcInterface>") preferred over
 *    transitive implementors via intermediate interfaces.
 * 5. "Primary JDBC interface match" (Bug 3 + 6): prefer candidates whose most
 *    specific implemented JDBC interface IS the target. This stops
 *    `SQLServerCallableStatement` (primary: CallableStatement) from being
 *    selected for `java.sql.Statement`, even though it transitively implements
 *    Statement and has the most declared methods.
 * 6. Leaf subclass preferred over its superclass when both are candidates
 *    (Bug 3): `SQLServerConnection43` extends `SQLServerConnection`, adds the
 *    JDBC 4.3 methods, and should win as the Connection entry class.
 * 7. Among remaining ties, the class with the most methods (richest impl).
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
        // Bug 2: include nested classes too — some drivers expose JDBC interface
        // implementations only via inner classes (e.g., MariaDB XAResource).
        // Selection logic below prefers top-level classes when available.
        val allClasses = compilationUnits.flatMap { cu ->
            cu.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { decl -> !decl.isInterface }
        }

        val jdbcImplementors = findJdbcImplementors(allClasses)
        val result = selectBestImplementor(jdbcImplementors).toMutableMap()

        // Apply explicit overrides: bypass auto-detection for specified interfaces
        for ((jdbcInterface, classFqcn) in overrides) {
            val classDecl = findClassByFqcn(allClasses, classFqcn)
            if (classDecl != null) {
                result[jdbcInterface] = classDecl
            } else {
                throw IllegalStateException(
                    "Entry class '$classFqcn' (pinned for $jdbcInterface) not found in parsed sources. " +
                        "Refusing to fall back to heuristics so daily numbers can't silently shift — " +
                        "update the profile/--entry-class pin.",
                )
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
     * Tracks whether each class directly declares "implements <interface>" and
     * which JDBC interfaces the class implements in total (needed for
     * "primary interface" tie-breaking in selectBestImplementor).
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
                    .add(ImplementorInfo(classDecl, isDirect, allInterfaces))
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
     * Select the best implementing class for each JDBC interface, applying the
     * priority chain documented on the class.
     *
     * Each filter narrows the candidate pool; when a filter would eliminate
     * everyone, it is skipped (fallback semantics).
     */
    private fun selectBestImplementor(
        implementors: Map<String, List<ImplementorInfo>>,
    ): Map<String, ClassOrInterfaceDeclaration> {
        return implementors.mapValues { (targetIface, infos) ->
            if (infos.size == 1) {
                return@mapValues infos.first().classDecl
            }

            // Priority 1: concrete > abstract
            val concrete = infos.filter { !it.classDecl.isAbstract }
            var candidates = concrete.ifEmpty { infos }

            // Priority 2 (Bug 2): top-level > nested. Falls back to nested when no
            // top-level candidate exists (e.g., MariaDB's MariaDbXAPinnedResource).
            val topLevel = candidates.filter { !it.classDecl.isNestedType }
            candidates = topLevel.ifEmpty { candidates }

            // Priority 3 (Bug 5): clean name > wrapper/proxy. Promoted ahead of
            // the "direct implementor" filter so a literal Wrapper class can't
            // outrank a non-wrapper that implements the interface transitively.
            val clean = candidates.filter { isMainImplClass(it.classDecl) }
            candidates = clean.ifEmpty { candidates }

            // Priority 4: direct implementor > transitive
            val direct = candidates.filter { it.isDirect }
            candidates = direct.ifEmpty { candidates }

            // Priority 5 (Bug 3 + 6): candidates whose PRIMARY JDBC interface
            // (most specific one they implement) matches the target. Stops
            // SQLServerCallableStatement (primary: CallableStatement) from
            // being picked as the Statement entry class.
            val primaryMatch = candidates.filter { info ->
                primaryJdbcInterface(info.allJdbcInterfaces) == targetIface
            }
            candidates = primaryMatch.ifEmpty { candidates }

            // Priority 6 (Bug 3): leaf > superclass within the candidate set.
            // Lets SQLServerConnection43 win over SQLServerConnection.
            val leaves = candidates.filter { info ->
                candidates.none { other ->
                    other !== info && classExtends(other.classDecl, info.classDecl)
                }
            }
            candidates = leaves.ifEmpty { candidates }

            // Priority 7: richest implementation (most declared methods)
            candidates.maxByOrNull { it.classDecl.methods.size }?.classDecl
                ?: infos.first().classDecl
        }
    }

    /**
     * Bug 3 + 6: the "primary" JDBC interface a class implements is the most
     * specific one — i.e., one that has no JDBC sub-interface in the
     * implemented set. Returns null if the class implements no JDBC interface
     * (shouldn't happen for our candidates) or if no clear leaf exists.
     *
     * Examples:
     *   SQLServerStatement       → {Statement}        → primary = Statement
     *   SQLServerPreparedStatement → {Statement, PreparedStatement, Wrapper}
     *                              → primary = PreparedStatement (Statement is
     *                                its super-interface, Wrapper is unrelated)
     *   SQLServerCallableStatement → {Statement, PreparedStatement,
     *                                  CallableStatement, Wrapper}
     *                              → primary = CallableStatement
     *   SQLServerConnection      → {Connection, Wrapper}        → primary = Connection
     *   SQLServerConnection43    → {Connection, Wrapper}        → primary = Connection
     *
     * For ties (multiple JDBC interfaces that are mutually unrelated, e.g.
     * Connection + Wrapper), we prefer the one that is NOT a low-level utility
     * interface — practically, Wrapper is always demoted because every JDBC
     * impl implements it.
     */
    private fun primaryJdbcInterface(allJdbcInterfaces: Set<String>): String? {
        if (allJdbcInterfaces.isEmpty()) return null
        // Find interfaces that have no sub-interface in the set.
        val leaves = allJdbcInterfaces.filter { iface ->
            allJdbcInterfaces.none { other ->
                other != iface && isJdbcSubinterface(other, iface)
            }
        }
        // Demote Wrapper — it's implemented by virtually every JDBC class and is
        // never the "primary" identity of a class.
        val nonWrapperLeaves = leaves.filter { it != "java.sql.Wrapper" }
        val pool = nonWrapperLeaves.ifEmpty { leaves }
        return pool.firstOrNull()
    }

    /**
     * Returns true if `sub` extends `parent` (transitively) in the JDBC
     * interface hierarchy table.
     */
    private fun isJdbcSubinterface(sub: String, parent: String): Boolean =
        JDBC_INTERFACE_ANCESTORS[sub]?.contains(parent) == true

    /**
     * Bug 3: returns true if `child` extends `ancestor` transitively (via the
     * class extends chain). Uses Symbol Solver's `getAllAncestors`, which
     * returns every supertype reachable through `extends` and `implements`.
     * Falls back to simple-name walking when type resolution fails.
     */
    private fun classExtends(
        child: ClassOrInterfaceDeclaration,
        ancestor: ClassOrInterfaceDeclaration,
    ): Boolean {
        if (child === ancestor) return false
        val ancestorFqn = ancestor.fullyQualifiedName.orElse(null)
        if (ancestorFqn != null) {
            try {
                return child.resolve().getAllAncestors().any { it.qualifiedName == ancestorFqn }
            } catch (_: Exception) {
                // fall through to simple-name walk
            }
        }
        // Fallback: walk `extends` chain by simple name
        val ancestorName = ancestor.nameAsString
        var currentExtends = child.extendedTypes.firstOrNull()?.nameAsString
        val visited = mutableSetOf<String>()
        while (currentExtends != null) {
            if (currentExtends == ancestorName) return true
            if (!visited.add(currentExtends)) return false
            currentExtends = null // no registry available here; stop walking
        }
        return false
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
        val allJdbcInterfaces: Set<String>,
    )

    companion object {
        private val JDBC_INTERFACE_SIMPLE_NAMES = setOf(
            // java.sql
            "Connection", "Statement", "PreparedStatement", "CallableStatement",
            "ResultSet", "DatabaseMetaData", "ResultSetMetaData", "ParameterMetaData",
            "Driver", "DriverAction",
            "Blob", "Clob", "NClob", "SQLXML",
            "Array", "Struct", "Ref", "RowId",
            "SQLData", "SQLInput", "SQLOutput", "SQLType",
            "Savepoint", "Wrapper",
            "ConnectionBuilder", "ShardingKey", "ShardingKeyBuilder",
            // javax.sql
            "DataSource", "ConnectionPoolDataSource", "CommonDataSource",
            "PooledConnection", "PooledConnectionBuilder",
            "ConnectionEventListener", "StatementEventListener",
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
            "DriverAction" to "java.sql.DriverAction",
            "Blob" to "java.sql.Blob",
            "Clob" to "java.sql.Clob",
            "NClob" to "java.sql.NClob",
            "SQLXML" to "java.sql.SQLXML",
            "Array" to "java.sql.Array",
            "Struct" to "java.sql.Struct",
            "Ref" to "java.sql.Ref",
            "RowId" to "java.sql.RowId",
            "SQLData" to "java.sql.SQLData",
            "SQLInput" to "java.sql.SQLInput",
            "SQLOutput" to "java.sql.SQLOutput",
            "SQLType" to "java.sql.SQLType",
            "Savepoint" to "java.sql.Savepoint",
            "Wrapper" to "java.sql.Wrapper",
            "ConnectionBuilder" to "java.sql.ConnectionBuilder",
            "ShardingKey" to "java.sql.ShardingKey",
            "ShardingKeyBuilder" to "java.sql.ShardingKeyBuilder",
            // javax.sql
            "DataSource" to "javax.sql.DataSource",
            "ConnectionPoolDataSource" to "javax.sql.ConnectionPoolDataSource",
            "CommonDataSource" to "javax.sql.CommonDataSource",
            "PooledConnection" to "javax.sql.PooledConnection",
            "PooledConnectionBuilder" to "javax.sql.PooledConnectionBuilder",
            "ConnectionEventListener" to "javax.sql.ConnectionEventListener",
            "StatementEventListener" to "javax.sql.StatementEventListener",
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

        /**
         * Bug 3 + 6: JDBC interface hierarchy used for "primary interface"
         * tie-breaking. Maps each JDBC interface to the SET of JDBC interfaces
         * it transitively extends (its ancestors in the spec hierarchy).
         *
         * Used by `primaryJdbcInterface()` to determine which interface a
         * candidate class is "most specifically" an implementation of.
         *
         * Source of truth: java.sql / javax.sql / javax.transaction.xa
         * Javadoc as of JDK 21.
         */
        private val JDBC_INTERFACE_ANCESTORS: Map<String, Set<String>> = mapOf(
            // java.sql.PreparedStatement extends Statement, AutoCloseable, Wrapper
            "java.sql.PreparedStatement" to setOf(
                "java.sql.Statement",
                "java.sql.Wrapper",
            ),
            // java.sql.CallableStatement extends PreparedStatement
            "java.sql.CallableStatement" to setOf(
                "java.sql.PreparedStatement",
                "java.sql.Statement",
                "java.sql.Wrapper",
            ),
            // java.sql.Statement extends Wrapper, AutoCloseable
            "java.sql.Statement" to setOf("java.sql.Wrapper"),
            // java.sql.Connection extends Wrapper, AutoCloseable
            "java.sql.Connection" to setOf("java.sql.Wrapper"),
            // java.sql.ResultSet extends Wrapper, AutoCloseable
            "java.sql.ResultSet" to setOf("java.sql.Wrapper"),
            // java.sql.DatabaseMetaData extends Wrapper
            "java.sql.DatabaseMetaData" to setOf("java.sql.Wrapper"),
            // java.sql.ParameterMetaData extends Wrapper
            "java.sql.ParameterMetaData" to setOf("java.sql.Wrapper"),
            // java.sql.ResultSetMetaData extends Wrapper
            "java.sql.ResultSetMetaData" to setOf("java.sql.Wrapper"),
            // java.sql.NClob extends Clob
            "java.sql.NClob" to setOf("java.sql.Clob"),
            // javax.sql.DataSource extends CommonDataSource, Wrapper
            "javax.sql.DataSource" to setOf(
                "javax.sql.CommonDataSource",
                "java.sql.Wrapper",
            ),
            // javax.sql.ConnectionPoolDataSource extends CommonDataSource
            "javax.sql.ConnectionPoolDataSource" to setOf("javax.sql.CommonDataSource"),
            // javax.sql.XADataSource extends CommonDataSource
            "javax.sql.XADataSource" to setOf("javax.sql.CommonDataSource"),
            // javax.sql.XAConnection extends PooledConnection
            "javax.sql.XAConnection" to setOf("javax.sql.PooledConnection"),
        )
    }
}
