package com.jdbcchecker.spec.extractor

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.javadoc.Javadoc
import com.jdbcchecker.model.JdbcVersion
import com.jdbcchecker.model.MethodSignature
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Extracts JDBC specification method definitions from JDK source files.
 *
 * Parses java.sql.* and javax.sql.* interface source files from the JDK,
 * extracting method signatures and @since version tags to build a complete
 * JDBC specification database.
 */
class SpecExtractor(private val jdkSourceRoot: Path) {

    /**
     * Extract all JDBC interface methods from the JDK source.
     *
     * @param targetInterfaces If specified, only extract from these interfaces.
     *                         If empty, extract from all interfaces found.
     */
    fun extractAll(targetInterfaces: Set<String> = emptySet()): List<MethodSignature> {
        val javaFiles = findJdbcInterfaceFiles()
        return javaFiles.flatMap { file ->
            extractFromFile(file, targetInterfaces)
        }
    }

    /**
     * Extract methods from a single Java source file.
     */
    fun extractFromFile(
        file: Path,
        targetInterfaces: Set<String> = emptySet(),
    ): List<MethodSignature> {
        val cu: CompilationUnit = try {
            StaticJavaParser.parse(file)
        } catch (e: Exception) {
            System.err.println("Warning: Failed to parse ${file}: ${e.message}")
            return emptyList()
        }

        return cu.findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { it.isInterface }
            .filter { decl ->
                if (targetInterfaces.isEmpty()) true
                else {
                    val fqn = resolveFullyQualifiedName(cu, decl)
                    fqn in targetInterfaces
                }
            }
            .flatMap { decl ->
                extractMethodsFromInterface(cu, decl)
            }
    }

    /**
     * Extract all method signatures from a single interface declaration.
     */
    private fun extractMethodsFromInterface(
        cu: CompilationUnit,
        decl: ClassOrInterfaceDeclaration,
    ): List<MethodSignature> {
        val interfaceName = resolveFullyQualifiedName(cu, decl)
        val interfaceSince = extractSinceTag(decl)

        return decl.methods.map { method ->
            val methodSince = extractSinceTag(method) ?: interfaceSince
            val jdbcVersion = methodSince
                ?.let { JdkVersionMapping.toJdbcVersion(it) }
                ?: guessVersionFromInterface(interfaceName)

            MethodSignature(
                interfaceName = interfaceName,
                methodName = method.nameAsString,
                parameterTypes = method.parameters.map { param ->
                    simplifyTypeName(param.type.asString())
                },
                returnType = simplifyTypeName(method.type.asString()),
                jdbcVersion = jdbcVersion,
            )
        }
    }

    /**
     * Extract the @since tag value from a Javadoc comment.
     */
    private fun extractSinceTag(node: com.github.javaparser.ast.nodeTypes.NodeWithJavadoc<*>): String? {
        val javadoc: Javadoc = node.javadoc.orElse(null) ?: return null
        val sinceTag = javadoc.blockTags.find { it.tagName == "since" }
            ?: return null
        return sinceTag.content.toText().trim()
    }

    /**
     * Build fully qualified name from package + class name.
     */
    private fun resolveFullyQualifiedName(
        cu: CompilationUnit,
        decl: ClassOrInterfaceDeclaration,
    ): String {
        val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (pkg.isEmpty()) decl.nameAsString else "$pkg.${decl.nameAsString}"
    }

    /**
     * Simplify generic type names for readability.
     * e.g., "java.util.Map<String, Class<?>>" → "Map"
     */
    private fun simplifyTypeName(typeName: String): String {
        // Remove generic parameters for matching purposes
        val baseType = typeName.substringBefore('<').trim()
        // Use simple name if it contains a dot
        return baseType.substringAfterLast('.')
    }

    /**
     * Find all Java source files under java/sql/ and javax/sql/ directories.
     */
    private fun findJdbcInterfaceFiles(): List<Path> {
        val sqlDir = jdkSourceRoot.resolve("java/sql")
        val javaxDir = jdkSourceRoot.resolve("javax/sql")

        return listOfNotNull(sqlDir, javaxDir)
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                Files.walk(dir)
                    .asSequence()
                    .filter { it.isRegularFile() && it.extension == "java" }
                    .filter { !it.fileName.toString().startsWith("package-info") }
                    .toList()
            }
    }

    /**
     * Guess JDBC version based on when the interface itself was introduced.
     * Used as fallback when @since tag is missing on individual methods.
     */
    private fun guessVersionFromInterface(interfaceName: String): JdbcVersion {
        return INTERFACE_VERSIONS[interfaceName] ?: JdbcVersion.V1_0
    }

    companion object {
        /**
         * Known introduction versions for JDBC interfaces.
         * Used as fallback when @since is missing.
         */
        private val INTERFACE_VERSIONS = mapOf(
            // JDBC 1.0
            "java.sql.Connection" to JdbcVersion.V1_0,
            "java.sql.Statement" to JdbcVersion.V1_0,
            "java.sql.PreparedStatement" to JdbcVersion.V1_0,
            "java.sql.CallableStatement" to JdbcVersion.V1_0,
            "java.sql.ResultSet" to JdbcVersion.V1_0,
            "java.sql.DatabaseMetaData" to JdbcVersion.V1_0,
            "java.sql.ResultSetMetaData" to JdbcVersion.V1_0,
            "java.sql.Driver" to JdbcVersion.V1_0,

            // JDBC 2.0
            "java.sql.Blob" to JdbcVersion.V2_0,
            "java.sql.Clob" to JdbcVersion.V2_0,
            "java.sql.Array" to JdbcVersion.V2_0,
            "java.sql.Struct" to JdbcVersion.V2_0,
            "java.sql.Ref" to JdbcVersion.V2_0,
            "javax.sql.DataSource" to JdbcVersion.V2_0,
            "javax.sql.ConnectionPoolDataSource" to JdbcVersion.V2_0,
            "javax.sql.CommonDataSource" to JdbcVersion.V2_0,
            "javax.sql.PooledConnection" to JdbcVersion.V2_0,
            "javax.sql.XAConnection" to JdbcVersion.V2_0,
            "javax.sql.XADataSource" to JdbcVersion.V2_0,

            // JDBC 3.0
            "java.sql.ParameterMetaData" to JdbcVersion.V3_0,
            "java.sql.Savepoint" to JdbcVersion.V3_0,

            // JDBC 4.0
            "java.sql.NClob" to JdbcVersion.V4_0,
            "java.sql.SQLXML" to JdbcVersion.V4_0,
            "java.sql.RowId" to JdbcVersion.V4_0,
            "java.sql.Wrapper" to JdbcVersion.V4_0,

            // JDBC 4.3
            "java.sql.ConnectionBuilder" to JdbcVersion.V4_3,
            "java.sql.ShardingKey" to JdbcVersion.V4_3,
            "java.sql.ShardingKeyBuilder" to JdbcVersion.V4_3,
        )
    }
}
