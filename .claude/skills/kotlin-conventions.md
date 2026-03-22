---
name: kotlin-conventions
description: >
  Enforce idiomatic Kotlin patterns and this project's coding conventions.
  Automatically apply when writing or reviewing Kotlin code in this project.
  Also trigger on: "Kotlin 스타일", "코틀린 관용구", "이거 코틀린답게", "kotlin convention".
trigger: always
---

# Kotlin Conventions Skill

## Purpose

Ensure all Kotlin code in this project is idiomatic and consistent.
The developer is new to Kotlin, so proactively convert Java habits into proper Kotlin patterns.

## Project-Specific Rules

### 1. Domain Model → data class

```kotlin
// GOOD
data class MethodSignature(
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val jdbcVersion: JdbcVersion,
)

// BAD — Java-style POJO
class MethodSignature {
    private var name: String = ""
    fun getName(): String = name
    fun setName(name: String) { this.name = name }
}
```

### 2. State/Status → sealed class/interface

```kotlin
// GOOD
sealed interface ImplementationStatus {
    data object NotFound : ImplementationStatus
    data object Stub : ImplementationStatus
    data class Implemented(val confidence: Float) : ImplementationStatus
}

// Exhaustive when — compiler enforces all cases are handled
fun describe(status: ImplementationStatus): String = when (status) {
    is ImplementationStatus.NotFound -> "Not found"
    is ImplementationStatus.Stub -> "Stub"
    is ImplementationStatus.Implemented -> "Implemented (${status.confidence})"
}
```

### 3. Enum → enum class

```kotlin
enum class JdbcVersion(val display: String) {
    V1_0("1.0"),
    V2_0("2.0"),
    V3_0("3.0"),
    V4_0("4.0"),
    V4_1("4.1"),
    V4_2("4.2"),
    V4_3("4.3"),
}
```

### 4. Nullable Handling

```kotlin
// GOOD — safe calls, elvis operator
val className = declaration.fullyQualifiedName?.asString() ?: return
val methods = classDecl?.methods.orEmpty()

// BAD — Java-style null checks
if (className != null) { ... }
```

### 5. Collection Operations

```kotlin
// GOOD — functional chain
val unimplemented = results
    .filter { it.status is ImplementationStatus.NotFound }
    .sortedBy { it.method.name }
    .groupBy { it.method.jdbcVersion }

// BAD — imperative loop
val unimplemented = mutableListOf<MethodResult>()
for (result in results) {
    if (result.status is ImplementationStatus.NotFound) {
        unimplemented.add(result)
    }
}
```

### 6. Extension Functions

```kotlin
// GOOD — extend existing types for readability
fun CompilationUnit.findJdbcImplementors(): List<ClassOrInterfaceDeclaration> =
    findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { it.implementsJdbcInterface() }

fun ClassOrInterfaceDeclaration.implementsJdbcInterface(): Boolean =
    implementedTypes.any { it.nameAsString in JDBC_INTERFACES }
```

### 7. String Templates

```kotlin
// GOOD
println("Coverage: ${coverage.percentage}% (${coverage.implemented}/${coverage.total})")

// BAD
println("Coverage: " + coverage.percentage + "% (" + coverage.implemented + "/" + coverage.total + ")")
```

### 8. Scope Functions (use sparingly)

```kotlin
// apply — object configuration
val config = AnalyzerConfig().apply {
    sourcePath = path
    outputFormats = listOf(OutputFormat.CONSOLE, OutputFormat.JSON)
}

// let — nullable transformation
file?.let { parseSource(it) }

// also — side effects such as logging
result.also { logger.info("Analysis complete: ${it.summary()}") }
```

## File Structure Convention

```kotlin
package com.jdbcchecker.analyzer

import ...

// 1. Top-level constants
private val JDBC_INTERFACES = setOf("Connection", "Statement", ...)

// 2. Main class
class SourceAnalyzer(...) {
    // Properties
    // Init block (if needed)
    // Public methods
    // Private methods
}

// 3. Extension functions related to the main class
private fun ClassOrInterfaceDeclaration.isStub(): Boolean = ...

// 4. Small related data classes
data class AnalysisConfig(...)
```

## Naming Convention

| Element | Style | Example |
|---|---|---|
| Package | lowercase, dot-separated | `com.jdbcchecker.analyzer` |
| Class | PascalCase | `SourceAnalyzer` |
| Function | camelCase | `analyzeSource()` |
| Property | camelCase | `methodCount` |
| Constant | SCREAMING_SNAKE_CASE | `JDBC_INTERFACES` |
| File | PascalCase (match class name) | `SourceAnalyzer.kt` |

## Testing Convention

```kotlin
@Test
fun `should detect stub method that throws UnsupportedOperationException`() {
    // Given
    val source = """
        public class MyConnection implements Connection {
            public void setSchema(String schema) {
                throw new UnsupportedOperationException();
            }
        }
    """.trimIndent()

    // When
    val result = analyzer.analyze(source)

    // Then
    assertThat(result.status).isEqualTo(ImplementationStatus.Stub)
}
```

- Test function names: natural language wrapped in backticks, written in English
- Structure: Given-When-Then
- Assertion library: AssertJ (`assertThat`)
