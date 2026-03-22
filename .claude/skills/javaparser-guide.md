---
name: javaparser-guide
description: >
  Guide for using JavaParser and Symbol Solver APIs in this project.
  Use when writing parsing/analysis code, configuring Symbol Solver,
  or troubleshooting AST traversal issues.
  Trigger on: "JavaParser", "Symbol Solver", "AST", "파싱", "파서 설정".
trigger: always
---

# JavaParser Guide Skill

## Purpose

Provide ready-to-use patterns for JavaParser + Symbol Solver-based source code analysis.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // This single dependency includes both javaparser-core and symbol-solver
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.4")
}
```

## Symbol Solver Setup

```kotlin
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver

fun configureParser(sourcePaths: List<Path>) {
    val typeSolver = CombinedTypeSolver().apply {
        add(ReflectionTypeSolver())             // JDK types (java.sql.*, etc.)
        sourcePaths.forEach { add(JavaParserTypeSolver(it)) }  // Project sources
    }

    val symbolSolver = JavaSymbolSolver(typeSolver)
    StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver)
}
```

### TypeSolver Priority
1. `ReflectionTypeSolver` — resolves JDK classes (java.sql.Connection, etc.)
2. `JavaParserTypeSolver` — resolves classes in the source under analysis
3. `JarTypeSolver` — (future) resolves external library JAR dependencies

## Common AST Patterns

### Find all classes implementing a specific interface

```kotlin
fun findImplementors(cu: CompilationUnit, interfaceName: String): List<ClassOrInterfaceDeclaration> =
    cu.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { classDecl ->
            classDecl.implementedTypes.any { it.nameAsString == interfaceName }
        }
```

### Find JDBC implementors using Symbol Solver (resolves fully qualified names)

```kotlin
fun findJdbcImplementors(cu: CompilationUnit): List<ClassOrInterfaceDeclaration> =
    cu.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { classDecl ->
            try {
                val resolved = classDecl.resolve()
                // Use getAllAncestors() — allInterfaces is not available on ResolvedReferenceTypeDeclaration
                resolved.getAllAncestors().any { ancestor ->
                    ancestor.qualifiedName.startsWith("java.sql.") ||
                    ancestor.qualifiedName.startsWith("javax.sql.")
                }
            } catch (e: Exception) {
                false // Symbol resolution failed — skip silently
            }
        }
```

### Check method body content

```kotlin
fun analyzeMethodBody(method: MethodDeclaration): ImplementationStatus {
    val body = method.body.orElse(null) ?: return ImplementationStatus.NotFound

    val statements = body.statements
    if (statements.isEmpty()) return ImplementationStatus.ReturnsDefault

    if (statements.size == 1) {
        val stmt = statements[0]
        when {
            stmt.isThrowStmt -> {
                val throwExpr = stmt.asThrowStmt().expression.toString()
                return when {
                    "UnsupportedOperationException" in throwExpr -> ImplementationStatus.ThrowsUnsupported
                    "SQLException" in throwExpr -> ImplementationStatus.ThrowsSqlException
                    else -> ImplementationStatus.ThrowsUnsupported
                }
            }
            stmt.isReturnStmt -> {
                val expr = stmt.asReturnStmt().expression.orElse(null)
                if (expr == null || isDefaultValue(expr)) return ImplementationStatus.ReturnsDefault
                if (expr.isMethodCallExpr) return ImplementationStatus.Delegates
            }
        }
    }

    return ImplementationStatus.FullyImplemented
}

fun isDefaultValue(expr: Expression): Boolean = when {
    expr.isNullLiteralExpr -> true
    expr.isBooleanLiteralExpr -> !expr.asBooleanLiteralExpr().value
    expr.isIntegerLiteralExpr -> expr.asIntegerLiteralExpr().value == "0"
    expr.isLongLiteralExpr -> expr.asLongLiteralExpr().value in listOf("0L", "0l", "0")
    expr.isStringLiteralExpr -> expr.asStringLiteralExpr().value.isEmpty()
    else -> false
}
```

### Method signature matching

```kotlin
fun matchesSpec(implMethod: MethodDeclaration, specMethod: MethodSignature): Boolean =
    implMethod.nameAsString == specMethod.methodName &&
    implMethod.parameters.size == specMethod.parameterTypes.size &&
    implMethod.parameters.zip(specMethod.parameterTypes).all { (param, specType) ->
        param.type.asString() == specType ||
        param.type.resolve().describe() == specType  // fully qualified fallback
    }
```

## Traversal Patterns

### VoidVisitorAdapter (side-effect traversal)

```kotlin
class MethodCollector : VoidVisitorAdapter<MutableList<MethodDeclaration>>() {
    override fun visit(n: MethodDeclaration, collector: MutableList<MethodDeclaration>) {
        super.visit(n, collector)
        collector.add(n)
    }
}

val methods = mutableListOf<MethodDeclaration>()
cu.accept(MethodCollector(), methods)
```

### GenericVisitorAdapter (value-returning traversal)

```kotlin
class PublicMethodCounter : GenericVisitorAdapter<Int, Void?>() {
    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Int =
        n.methods.count { it.isPublic }
}
```

## Common Pitfalls

1. **Symbol Solver throws exceptions** — always wrap `resolve()` in try-catch; fail silently with fallback logic
2. **`allInterfaces` does not exist** — use `getAllAncestors()` on `ResolvedReferenceTypeDeclaration` instead
3. **Generic types** — `List<String>` vs `List`; use `type.resolve().describe()` for fully qualified matching
4. **Inner classes** — `findAll()` recurses into inner classes; filter with `isTopLevelType` if needed
5. **Default interface methods** — Java 8+ interface default methods have a body; distinguish from class methods
6. **StaticJavaParser is global** — Symbol Solver config is static; call `setSymbolResolver` once at startup only
