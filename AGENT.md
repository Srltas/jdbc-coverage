# JDBC Compliance Checker — Project Agent Guide

## Project Identity

- **Name**: jdbc-compliance-checker
- **Language**: Kotlin (JVM 21)
- **Build**: Gradle (Kotlin DSL)
- **Purpose**: A CLI tool that statically analyzes JDBC driver source code to measure JDBC spec compliance automatically.

## Core Objective

> **Automatically verify the API implementation status of a JDBC driver based on its source code.**
>
> Stay focused on this single goal. CI gating, dynamic analysis, and other extras are deferred until the core is stable.

## Key Design Decisions

### 1. Source Input
- Supports both **local path** (`--source-path ./src`) and **Git URL** (`--git-url https://...`)
- Git history analysis (tracking coverage changes per commit) is deferred to v1.0+
- MVP focuses on **current snapshot analysis** only

### 2. JDBC Spec Mapping
- **Auto-extract from JDK source (OpenJDK)** → export to YAML → manage in Git
- Targets all public methods in `java.sql.*` and `javax.sql.*` interfaces
- Classified by JDBC version: 1.0, 2.0, 3.0, 4.0, 4.1, 4.2, 4.3
- The spec extractor itself is the first milestone of the project

### 3. Implementation Level Detection
- Designed for **Level 2 (detailed)**, implemented progressively starting from Level 1
- Level 1 (MVP):
  - `NOT_FOUND` — method does not exist in the driver source
  - `STUB` — throws UnsupportedOperationException or returns a default value
  - `IMPLEMENTED` — contains real logic
- Level 2 (v0.2):
  - `NOT_FOUND`
  - `THROWS_UNSUPPORTED` — throws new UnsupportedOperationException
  - `THROWS_SQLEXCEPTION` — throws new SQLException("Not supported")
  - `RETURNS_DEFAULT` — returns null / 0 / false / ""
  - `DELEGATES` — only delegates to another method
  - `PARTIAL` — handles only some parameter combinations
  - `FULLY_IMPLEMENTED` — complete implementation

### 4. Inheritance Chain Resolution
- **Auto-discovery**: automatically finds classes that implement `java.sql.*` / `javax.sql.*`
- When multiple classes implement the same interface, selects the **most concrete (leaf) class** in the hierarchy
- `--entry-class` option available for manual override
- Uses JavaParser Symbol Solver to resolve the full inheritance chain

### 5. Report Output
- **Console**: per-interface coverage summary + list of unimplemented methods
- **JSON**: structured output for CI integration and data analysis
- **HTML** (v0.2): single HTML file using kotlinx.html + embedded Chart.js
- Multiple outputs supported simultaneously: `--output console --output json:result.json`

### 6. CI Integration
- **Excluded for now** — stay focused on the core goal of verifying API implementation status
- Good JSON output is sufficient; external CI tools can handle the rest
- `--fail-under`, PR comments, etc. are deferred to future versions

## Tech Stack

| Category | Choice | Reason |
|---|---|---|
| Language | Kotlin | Full Java ecosystem + concise syntax |
| Build | Gradle (Kotlin DSL) | Standard for Kotlin projects |
| Java Parser | JavaParser + Symbol Solver | Best-in-class for inheritance chain resolution and type analysis |
| CLI | picocli | JVM CLI standard with annotation-based code generation |
| Spec Data | YAML (Jackson YAML) | Human-editable, version-control-friendly |
| HTML Report | kotlinx.html + Chart.js | Type-safe generation + zero external dependencies |
| Test | JUnit 5 + AssertJ | Standard test stack |

## Roadmap

### v0.1 — MVP
- [x] Gradle project initial structure
- [x] JDBC spec auto-extractor (OpenJDK source → YAML)
- [ ] Static analysis engine (Level 1 detection)
- [ ] Inheritance chain auto-discovery and resolution
- [ ] Console report output
- [ ] JSON report output
- [ ] Validation against real CUBRID JDBC source

### v0.2 — Enhanced Analysis
- [ ] Level 2 detailed detection
- [ ] HTML report (kotlinx.html + Chart.js)
- [ ] Analysis result caching

### v0.3 — Comparison
- [ ] Multi-driver source comparison
- [ ] Result diff against previous analysis

### v1.0 — Full Feature
- [ ] JAR analysis (Reflection-based)
- [ ] Git URL auto-clone support

### v1.5+ — Future
- [ ] Dynamic analysis (live DB connection)
- [ ] Git history tracking (coverage change per commit)

## Architecture Overview

```
jdbc-compliance-checker/
├── app/src/main/kotlin/com/jdbcchecker/
│   ├── model/       # Domain models: MethodSignature, AnalysisResult, etc.
│   ├── spec/        # JDBC spec loading from YAML + extractor from JDK source
│   │   └── extractor/
│   ├── parser/      # JavaParser-based source file parsing
│   ├── resolver/    # Inheritance chain resolution, interface mapping
│   ├── detector/    # Implementation level detection (Level 1, 2)
│   ├── report/      # Report generation
│   │   ├── console/
│   │   ├── json/
│   │   └── html/
│   └── cli/         # picocli CLI entry point
└── app/src/main/resources/jdbc-spec/  # YAML spec data
    ├── java.sql.Connection.yaml
    ├── java.sql.Statement.yaml
    └── ...
```

## Coding Conventions

- Follow official Kotlin coding conventions: https://kotlinlang.org/docs/coding-conventions.html
- Write KDoc on all public APIs
- Design immutable models with `data class`
- Use `sealed class` / `sealed interface` to represent states (e.g., `ImplementationStatus`)
- Prefer extension functions for readability
- Tests follow the Given-When-Then pattern

## Important Constraints

1. **Stay generic**: No CUBRID-specific logic. The tool must work with any JDBC driver source.
2. **Stay focused**: Verifying API implementation status is the only goal. Do not add unrelated features.
3. **Incremental delivery**: Level 1 → Level 2, console → HTML. Do not build everything at once.
4. **Accuracy over speed**: Incorrect detection destroys trust in the tool. Prefer correctness.
