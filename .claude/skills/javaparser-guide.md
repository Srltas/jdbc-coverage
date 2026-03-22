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

JavaParser + Symbol Solver를 사용한 소스 코드 분석 패턴을 제공한다.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.26.4")
}
```

이 하나의 의존성이 javaparser-core + symbol-solver를 모두 포함한다.

## Symbol Solver Setup

```kotlin
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver

fun createParser(sourcePaths: List<Path>): Unit {
    val typeSolver = CombinedTypeSolver().apply {
        // JDK types (java.sql.*, etc.)
        add(ReflectionTypeSolver())
        // Project source paths
        sourcePaths.forEach { add(JavaParserTypeSolver(it)) }
    }

    val symbolSolver = JavaSymbolSolver(typeSolver)
    StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver)
}
```

### TypeSolver Priority
1. `ReflectionTypeSolver` — JDK 클래스 해석 (java.sql.Connection 등)
2. `JavaParserTypeSolver` — 분석 대상 소스 해석
3. `JarTypeSolver` — (향후) 외부 라이브러리 JAR 의존성 해석

## Common AST Patterns

### Find all classes implementing a specific interface

```kotlin
fun findImplementors(cu: CompilationUnit, interfaceName: String): List<ClassOrInterfaceDeclaration> =
    cu.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { classDecl ->
            classDecl.implementedTypes.any { it.nameAsString == interfaceName }
        }
```

### Find with Symbol Solver (resolves full qualified names)

```kotlin
fun findJdbcImplementors(cu: CompilationUnit): List<ClassOrInterfaceDeclaration> =
    cu.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { classDecl ->
            try {
                val resolved = classDecl.resolve()
                resolved.allInterfaces.any {
                    it.qualifiedName.startsWith("java.sql.") ||
                    it.qualifiedName.startsWith("javax.sql.")
                }
            } catch (e: Exception) {
                false // Symbol resolution failed — skip
            }
        }
```

### Get all methods of a class (including inherited)

```kotlin
fun getAllMethods(classDecl: ClassOrInterfaceDeclaration): List<MethodDeclaration> {
    // Direct methods only
    val directMethods = classDecl.methods

    // Including inherited (requires Symbol Solver)
    val resolved = classDecl.resolve()
    val allMethods = resolved.allMethods // includes inherited
    return directMethods
}
```

### Check method body content

```kotlin
fun analyzeMethodBody(method: MethodDeclaration): ImplementationStatus {
    val body = method.body.orElse(null) ?: return ImplementationStatus.NotFound

    val statements = body.statements
    if (statements.isEmpty()) return ImplementationStatus.Stub

    // Single statement analysis
    if (statements.size == 1) {
        val stmt = statements[0]
        when {
            stmt.isThrowStmt -> {
                val throwExpr = stmt.asThrowStmt().expression
                return when {
                    throwExpr.toString().contains("UnsupportedOperationException") ->
                        ImplementationStatus.ThrowsUnsupported
                    throwExpr.toString().contains("SQLException") ->
                        ImplementationStatus.ThrowsSqlException
                    else -> ImplementationStatus.Stub
                }
            }
            stmt.isReturnStmt -> {
                val expr = stmt.asReturnStmt().expression.orElse(null)
                if (expr == null || isDefaultValue(expr)) {
                    return ImplementationStatus.ReturnsDefault
                }
            }
        }
    }

    return ImplementationStatus.FullyImplemented
}

fun isDefaultValue(expr: Expression): Boolean = when {
    expr.isNullLiteralExpr -> true
    expr.isBooleanLiteralExpr -> !expr.asBooleanLiteralExpr().value
    expr.isIntegerLiteralExpr -> expr.asIntegerLiteralExpr().value == "0"
    expr.isLongLiteralExpr -> expr.asLongLiteralExpr().value == "0L"
    expr.isDoubleLiteralExpr -> expr.asDoubleLiteralExpr().value == "0.0"
    expr.isStringLiteralExpr -> expr.asStringLiteralExpr().value.isEmpty()
    else -> false
}
```

### Method signature matching

```kotlin
data class MethodSignature(
    val name: String,
    val parameterTypes: List<String>,
)

fun MethodDeclaration.toSignature(): MethodSignature =
    MethodSignature(
        name = nameAsString,
        parameterTypes = parameters.map { it.type.asString() },
    )

fun matchesSpec(implMethod: MethodDeclaration, specMethod: MethodSignature): Boolean =
    implMethod.nameAsString == specMethod.name &&
    implMethod.parameters.size == specMethod.parameterTypes.size &&
    implMethod.parameters.zip(specMethod.parameterTypes).all { (param, specType) ->
        param.type.asString() == specType ||
        param.type.resolve().describe() == specType  // Fully qualified match
    }
```

## Traversal Patterns

### VoidVisitorAdapter (when you need side effects)

```kotlin
class MethodCollector : VoidVisitorAdapter<MutableList<MethodDeclaration>>() {
    override fun visit(n: MethodDeclaration, collector: MutableList<MethodDeclaration>) {
        super.visit(n, collector)
        collector.add(n)
    }
}

// Usage
val methods = mutableListOf<MethodDeclaration>()
cu.accept(MethodCollector(), methods)
```

### GenericVisitorAdapter (when you need return values)

```kotlin
class PublicMethodCounter : GenericVisitorAdapter<Int, Void?>() {
    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Int {
        return n.methods.count { it.isPublic }
    }
}
```

## Common Pitfalls

1. **Symbol Solver can throw** — Always wrap `resolve()` calls in try-catch
2. **Generic types** — `List<String>` vs `List` — use `type.resolve().describe()` for full type
3. **Inner classes** — `findAll()` recurses into inner classes; filter with `isTopLevelType` if needed
4. **Default methods** — Java 8+ interface default methods have a body; don't confuse with class methods
5. **StaticJavaParser is global** — Symbol Solver configuration is static; set it once at startup
