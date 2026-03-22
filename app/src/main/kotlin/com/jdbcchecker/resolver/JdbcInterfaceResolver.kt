package com.jdbcchecker.resolver

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.resolution.types.ResolvedReferenceType

/**
 * Resolves which classes in the source implement JDBC interfaces.
 *
 * Handles inheritance chains like:
 *   java.sql.Connection
 *     <- CUBRIDConnectionDefault (implements, mostly stubs)
 *       <- CUBRIDConnection (extends, actual implementation)
 *
 * For such chains, selects the most concrete (leaf) class.
 */
class JdbcInterfaceResolver {

    /**
     * Mapping from JDBC interface simple name to the implementing class declaration.
     * Selects the most concrete class in the inheritance chain.
     */
    fun resolve(compilationUnits: List<CompilationUnit>): Map<String, ClassOrInterfaceDeclaration> {
        val allClasses = compilationUnits.flatMap { cu ->
            cu.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { !it.isInterface }
        }

        val jdbcImplementors = findJdbcImplementors(allClasses)
        return selectMostConcreteClasses(jdbcImplementors)
    }

    /**
     * Find all classes that implement any JDBC interface (directly or transitively).
     */
    private fun findJdbcImplementors(
        classes: List<ClassOrInterfaceDeclaration>,
    ): Map<String, List<ClassOrInterfaceDeclaration>> {
        val result = mutableMapOf<String, MutableList<ClassOrInterfaceDeclaration>>()

        for (classDecl in classes) {
            val jdbcInterfaces = resolveJdbcInterfaces(classDecl)
            for (iface in jdbcInterfaces) {
                result.getOrPut(iface) { mutableListOf() }.add(classDecl)
            }
        }

        return result
    }

    /**
     * Resolve which JDBC interfaces a class implements (directly or via parent).
     */
    private fun resolveJdbcInterfaces(classDecl: ClassOrInterfaceDeclaration): Set<String> {
        return try {
            val resolved = classDecl.resolve()
            val allAncestors: List<ResolvedReferenceType> = resolved.getAllAncestors()
            allAncestors.map { ancestor ->
                ancestor.qualifiedName
            }.filter { name ->
                name.startsWith("java.sql.") || name.startsWith("javax.sql.")
            }.toSet()
        } catch (e: Exception) {
            // Fallback: check direct implements clause
            classDecl.implementedTypes.map { type ->
                type.nameAsString
            }.filter { name ->
                name in JDBC_INTERFACE_SIMPLE_NAMES
            }.toSet()
        }
    }

    /**
     * From multiple classes implementing the same interface,
     * select the most concrete (leaf) class in the inheritance chain.
     */
    private fun selectMostConcreteClasses(
        implementors: Map<String, List<ClassOrInterfaceDeclaration>>,
    ): Map<String, ClassOrInterfaceDeclaration> {
        return implementors.mapValues { (_, classes) ->
            if (classes.size == 1) {
                classes.first()
            } else {
                // Find the class that is not extended by any other class in the list
                val classNames = classes.map { it.nameAsString }.toSet()
                classes.find { classDecl ->
                    val parentName = classDecl.extendedTypes.firstOrNull()?.nameAsString
                    parentName in classNames
                } ?: classes.last()
            }
        }
    }

    companion object {
        private val JDBC_INTERFACE_SIMPLE_NAMES = setOf(
            "Connection", "Statement", "PreparedStatement", "CallableStatement",
            "ResultSet", "DatabaseMetaData", "ResultSetMetaData", "ParameterMetaData",
            "Driver", "Blob", "Clob", "NClob", "SQLXML", "Array", "Struct", "Ref",
            "Wrapper", "DataSource", "ConnectionPoolDataSource", "CommonDataSource",
        )
    }
}
