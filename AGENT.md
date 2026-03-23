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
- Selection strategy: prefer classes that **directly declare `implements <Interface>`**, filter out wrapper/XA/pooling patterns, break ties by method count
- `--entry-class` option available for manual override
- Uses JavaParser Symbol Solver to resolve the full inheritance chain
- Implementation detector walks up the parent class chain across files

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

### v0.1 — MVP ✅ Complete
- [x] Gradle project initial structure
- [x] JDBC spec auto-extractor (OpenJDK source → YAML)
- [x] Static analysis engine (Level 1 detection)
- [x] Inheritance chain auto-discovery and resolution
- [x] Console report output
- [x] JSON report output
- [x] Validation against real CUBRID JDBC source (62.3% coverage, 480/771 methods)

### v0.2 — Enhanced Analysis ✅ Complete
- [x] Level 2 detailed detection in console/JSON reports
- [x] HTML report (kotlinx.html + Chart.js) — single self-contained file with donut/bar charts
- [x] JDBC version breakdown in reports (console + HTML)

### v0.3 — Comparison ✅ Complete
- [x] `diff` command: compare baseline JSON vs current source (or two JSON reports)
- [x] `compare` command: side-by-side multi-driver source comparison
- [x] Console output for diff (improved/regressed/still missing)
- [x] Console output for comparison (coverage table + method matrix)
- [x] JSON output for both diff and comparison reports
- [x] Custom Jackson serializer for ImplementationStatus (enables JSON round-trip)

### v1.0 — Full Feature ✅ Complete
- [x] Git URL auto-clone support (shallow clone via system git)
- [x] `--branch` option for specifying branch/tag
- [x] `--source-subdir` option for JDBC source within repository
- [x] Auto driver name detection from Git URL
- [x] Temp directory cleanup after analysis

### v1.5+ — Future
- [ ] Driver profile configuration (see Known Issues below)
- [ ] Dynamic analysis (live DB connection)
- [ ] Git history tracking (coverage change per commit)

---

## Known Issues & Deferred Work

### Driver Profile Configuration

**배경 (Why this is needed)**

`--source-subdir` 옵션은 하나의 파싱 범위만 지정하며, 파싱 범위가 곧 클래스 선택 범위와 상속 체인 탐색 범위를 동시에 결정한다. 단순한 드라이버(CUBRID 등)에서는 이것으로 충분하지만, MySQL처럼 소스가 여러 모듈로 분리된 드라이버에서는 문제가 발생한다.

**실제로 발생한 문제**

MySQL Connector/J를 분석할 때 `--source-subdir` 조합에 따라 커버리지가 크게 달라졌다:

| 지정한 디렉터리 | 커버리지 |
|----------------|---------|
| `user-impl/java` + `core-impl/java` | 47.2% |
| `user-impl/java` + `user-api` | 53.7% |
| `user-impl/java` + `user-api` + `core-impl/java` + `core-api/java` | 71.7% |

원인: `ImplementationDetector`가 상속 체인을 탐색할 때, 파싱된 파일 안에서만 부모 클래스를 찾을 수 있다. 부모 클래스가 포함된 디렉터리를 지정하지 않으면 상속 체인이 중간에 끊겨 메서드가 `Not Found`로 잘못 분류된다.

**근본 원인**

현재 구조의 한계:
```
파싱 범위 = 클래스 선택 범위 + 상속 체인 탐색 범위
```
이 두 가지가 분리되어 있지 않다. 정확한 분석을 위해서는:
- **클래스 선택**: JDBC 인터페이스를 `implements`로 선언한 클래스만 후보
- **상속 체인 탐색**: 선택된 클래스의 전체 부모 클래스까지 파싱 가능해야 함

**해결 방안: 드라이버 프로파일 YAML**

드라이버별 분석 설정을 YAML 파일로 정의하고, `--profile` 옵션으로 로드하는 방식:

```yaml
# profiles/mysql.yaml
name: MySQL Connector/J
url: https://github.com/mysql/mysql-connector-j.git
sourceDirs:
  - src/main/user-impl/java   # JDBC 인터페이스 구현체 (클래스 선택 대상)
  - src/main/user-api/java    # 중간 인터페이스/추상 클래스
  - src/main/core-impl/java   # 부모 추상 클래스 (상속 체인 탐색용)
  - src/main/core-api/java    # 내부 API 인터페이스
entryClasses:                 # (선택) 클래스 선택을 수동으로 고정
  - com.mysql.cj.jdbc.ConnectionImpl
  - com.mysql.cj.jdbc.StatementImpl
  - com.mysql.cj.jdbc.result.ResultSetImpl
```

```bash
# 프로파일 사용 예시
jdbc-checker analyze --profile ./profiles/mysql.yaml -o html:./report.html
jdbc-checker analyze --profile mysql  # 번들 프로파일 참조
```

**구현 시 고려사항**

1. `ProfileLoader`: YAML → `AnalysisProfile` 데이터 클래스로 역직렬화
2. `AnalyzeCommand`: `--profile` 옵션 추가, `--source`, `--source-subdir`, `--entry-class`보다 낮은 우선순위
3. 번들 프로파일: `resources/profiles/` 에 주요 드라이버(cubrid, mysql, mariadb, postgresql, mssql) 기본 프로파일 포함
4. `sourceDirs`와 별도로 `inheritanceDirs`를 분리하는 방안도 고려 (클래스 선택에는 포함하지 않고 상속 탐색에만 사용)

**임시 해결책 (현재)**

`src/main` 또는 `src/main/java`처럼 상위 디렉터리를 통째로 지정하면 대부분의 경우 올바른 결과를 얻을 수 있다. 단, 이 경우 더 많은 파일을 파싱하므로 분석 시간이 늘어날 수 있다.

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
