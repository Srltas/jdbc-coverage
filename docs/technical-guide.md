# JDBC Compliance Checker 기술 문서

> **대상 독자:** 이 프로그램을 처음 접하는 개발자
> **목적:** 프로그램이 무엇을 하고, 어떤 원리로 분석하며, 내부가 어떻게 동작하는지를 코드 수준에서 상세히 설명

---

## 목차

1. [프로그램 개요](#1-프로그램-개요)
2. [프로젝트 구조](#2-프로젝트-구조)
3. [핵심 개념: "JDBC 구현율"이란?](#3-핵심-개념-jdbc-구현율이란)
4. [분석 파이프라인 전체 흐름](#4-분석-파이프라인-전체-흐름)
5. [Step 1: JDBC 스펙 로딩](#5-step-1-jdbc-스펙-로딩)
6. [Step 2: 소스 코드 파싱](#6-step-2-소스-코드-파싱)
7. [Step 3: JDBC 인터페이스 구현체 매핑](#7-step-3-jdbc-인터페이스-구현체-매핑)
8. [Step 4: 메서드별 구현 상태 분석](#8-step-4-메서드별-구현-상태-분석)
9. [구현율 계산 방식](#9-구현율-계산-방식)
10. [Entry Class Override 메커니즘](#10-entry-class-override-메커니즘)
11. [스펙 추출기 (SpecExtractor)](#11-스펙-추출기-specextractor)
12. [출력 리포터](#12-출력-리포터)
13. [Diff와 Compare 기능](#13-diff와-compare-기능)
14. [데이터 모델](#14-데이터-모델)
15. [빌드 및 의존성](#15-빌드-및-의존성)
16. [알려진 제약사항과 한계](#16-알려진-제약사항과-한계)

---

## 1. 프로그램 개요

### 1.1 이 프로그램이 하는 일

JDBC Compliance Checker는 **JDBC 드라이버의 소스 코드를 정적 분석하여 JDBC API 구현율을 측정하는 CLI 도구**이다.

구체적으로:
- JDBC 스펙에 정의된 모든 메서드(1,017개, 41개 인터페이스)를 기준으로
- 드라이버 소스 코드에서 각 메서드가 실제로 구현되었는지 분석하고
- 인터페이스별, JDBC 버전별, 전체 구현율을 계산하여 보고서를 생성한다

### 1.2 분석 대상 범위

이 도구가 검사하는 JDBC API 범위는 세 개의 Java 패키지에 걸쳐 있다:

| 패키지 | 모듈 | 설명 | 인터페이스 수 |
|--------|------|------|-------------|
| `java.sql.*` | `java.sql` | JDBC 핵심 인터페이스 | 17개 |
| `javax.sql.*` | `java.sql` | 확장 인터페이스 (DataSource, XA 등) | 22개 |
| `javax.transaction.xa.*` | `java.transaction.xa` | XA 분산 트랜잭션 | 2개 |

`javax.transaction.xa`가 포함되는 이유: `javax.sql.XAConnection.getXAResource()`가 `XAResource`를 반환하므로, XA 지원 드라이버는 반드시 이 인터페이스를 구현해야 한다. JDK 모듈 선언에서도 `java.sql` 모듈이 `java.transaction.xa`를 `requires transitive`로 의존한다.

### 1.3 제공 명령어

| 명령어 | 설명 |
|--------|------|
| `analyze` | 단일 드라이버 소스 분석 → 구현율 보고서 생성 |
| `diff` | 기준(baseline) JSON과 현재 소스/JSON 비교 → 개선/퇴보 추적 |
| `compare` | 2개 이상 드라이버 비교 → 나란히(side-by-side) 비교 보고서 |
| `extract-spec` | JDK 소스에서 JDBC 스펙 YAML 추출 |

---

## 2. 프로젝트 구조

### 2.1 디렉터리 레이아웃

```
java-compliance-checker/
├── app/
│   ├── build.gradle.kts              # Gradle 빌드 설정
│   └── src/main/
│       ├── kotlin/com/jdbcchecker/
│       │   ├── cli/                  # CLI 진입점 및 명령어
│       │   │   ├── Main.kt              # 메인 진입점, runAnalysis() 핵심 함수
│       │   │   ├── SourceResolver.kt     # 소스 경로 해석 (로컬/Git)
│       │   │   └── ExtractSpecCommand.kt # extract-spec 명령어
│       │   ├── model/                # 데이터 모델
│       │   │   ├── MethodSignature.kt    # 메서드 시그니처 정의
│       │   │   ├── ImplementationStatus.kt # 구현 상태 (7단계 분류)
│       │   │   ├── JdbcVersion.kt        # JDBC 버전 열거형
│       │   │   ├── AnalysisResult.kt     # 분석 결과 모델
│       │   │   ├── DiffResult.kt         # 차이 비교 모델
│       │   │   └── DriverComparisonReport.kt # 드라이버 비교 모델
│       │   ├── spec/                 # JDBC 스펙 로딩
│       │   │   ├── JdbcSpecLoader.kt     # YAML 스펙 파일 로더
│       │   │   └── extractor/            # JDK 소스에서 스펙 추출
│       │   │       ├── SpecExtractor.kt      # 추출 엔진
│       │   │       ├── JdkVersionMapping.kt  # JDK @since → JDBC 버전 매핑
│       │   │       └── YamlSpecWriter.kt     # YAML 파일 출력
│       │   ├── parser/               # 소스 코드 파싱
│       │   │   └── SourceParser.kt       # JavaParser 기반 AST 파서
│       │   ├── resolver/             # 인터페이스 구현체 탐색
│       │   │   └── JdbcInterfaceResolver.kt  # JDBC 인터페이스 → 구현 클래스 매핑
│       │   ├── detector/             # 메서드 구현 상태 감지
│       │   │   └── ImplementationDetector.kt # 메서드 본문 분석기
│       │   ├── report/               # 보고서 생성
│       │   │   ├── DiffEngine.kt         # diff/compare 계산 엔진
│       │   │   ├── console/              # 콘솔 텍스트 출력
│       │   │   ├── json/                 # JSON 파일 출력
│       │   │   └── html/                 # HTML 시각화 출력
│       │   └── git/                  # Git 연동
│       │       └── GitCloneService.kt    # shallow clone 서비스
│       └── resources/jdbc-spec/      # 번들된 JDBC 스펙 YAML 파일
│           ├── java.sql.Connection.yaml
│           ├── java.sql.Statement.yaml
│           ├── javax.sql.DataSource.yaml
│           ├── javax.transaction.xa.XAResource.yaml
│           ├── _summary.yaml
│           └── ... (총 41개 인터페이스 파일)
├── docs/                             # 문서
└── AGENT.md                          # AI 에이전트용 프로젝트 가이드
```

### 2.2 패키지별 역할 요약

```
cli       → 사용자 명령어 수신, 파이프라인 조율
model     → 순수 데이터 구조 (비즈니스 로직 없음)
spec      → "무엇을 검사할 것인가" (JDBC 스펙 메서드 목록)
parser    → "소스를 어떻게 읽을 것인가" (JavaParser AST 변환)
resolver  → "어떤 클래스가 어떤 인터페이스를 구현하는가" (매핑)
detector  → "각 메서드가 실제로 구현되었는가" (본문 분석)
report    → "결과를 어떻게 보여줄 것인가" (콘솔/JSON/HTML)
git       → Git URL 소스 clone 지원
```

---

## 3. 핵심 개념: "JDBC 구현율"이란?

### 3.1 개념 정의

JDBC 구현율이란 **JDBC 스펙에 정의된 전체 메서드 중, 드라이버가 실제로 구현한 메서드의 비율**이다.

```
구현율(%) = (구현된 메서드 수 ÷ 스펙 메서드 총 수) × 100
```

### 3.2 "구현"의 정의 — Level 1 분류

이 도구는 각 메서드를 3가지 상태로 **1차 분류(Level 1)** 한다:

| Level 1 상태 | 의미 | 구현율 계산 포함 |
|-------------|------|----------------|
| **IMPLEMENTED** | 실질적인 로직이 존재함 | **Yes** |
| **STUB** | 코드는 있으나 미구현 (예외 throw, 기본값 return 등) | No |
| **NOT_FOUND** | 메서드 자체가 없음 | No |

### 3.3 세부 분류 — Level 2

Level 1에서 IMPLEMENTED와 STUB으로 분류된 메서드를 더 세분화한 것이 **Level 2 분류**이다:

| Level 2 상태 | Level 1 | 설명 | 점수 |
|-------------|---------|------|-----|
| `FullyImplemented` | IMPLEMENTED | 실질적 로직으로 완전 구현 | 4 |
| `Delegates` | IMPLEMENTED | 다른 메서드에 위임 (return other.method()) | 3 |
| `Partial` | IMPLEMENTED | 일부 분기에서 UnsupportedOperationException | 2 |
| `ThrowsSqlException` | STUB | SQLException throw | 1 |
| `ThrowsUnsupported` | STUB | UnsupportedOperationException throw | 1 |
| `ReturnsDefault` | STUB | null, 0, false, "" 등 기본값만 반환 | 1 |
| `NotFound` | NOT_FOUND | 메서드가 존재하지 않음 | 0 |

`score()` 값은 diff 비교 시 개선/퇴보 판단에 사용된다. 점수가 올라가면 개선(IMPROVED), 내려가면 퇴보(REGRESSED)이다.

### 3.4 왜 이렇게 분류하는가?

단순히 "메서드가 있다/없다"만으로는 구현 품질을 판단할 수 없다. 많은 JDBC 드라이버가 모든 인터페이스 메서드를 형식적으로 override하면서 본문에 `throw new UnsupportedOperationException()`만 넣어두기 때문이다. 이 도구는 **메서드 본문의 AST(추상 구문 트리)를 실제로 분석**하여 이런 스텁을 정확히 걸러낸다.

---

## 4. 분석 파이프라인 전체 흐름

사용자가 `jdbc-checker analyze <source>` 명령을 실행하면 아래 파이프라인이 순서대로 실행된다.

```
┌────────────────────────────────────────────────────────────────┐
│ CLI 입력: jdbc-checker analyze <source> [옵션]                  │
└──────────────────────────┬─────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ SourceResolver: 소스 경로 해석                                 │
│  - Git URL → shallow clone (depth=1) → 임시 디렉터리          │
│  - 로컬 경로 → 그대로 사용                                     │
│  - --source-subdir 적용 → List<Path> 반환                     │
└──────────────────────────┬───────────────────────────────────┘
                           │ sourcePaths: List<Path>
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ runAnalysis() — 핵심 분석 함수                                 │
│                                                              │
│  ┌─────────────────────────────────┐                         │
│  │ Step 1: JDBC 스펙 로딩           │                         │
│  │ JdbcSpecLoader                  │                         │
│  │ → 771개 메서드, 19개 인터페이스   │                         │
│  └────────────────┬────────────────┘                         │
│                   │ specByInterface: Map<String, List<MethodSignature>>
│                   ▼                                          │
│  ┌─────────────────────────────────┐                         │
│  │ Step 2: 소스 파일 파싱           │                         │
│  │ SourceParser (JavaParser)       │                         │
│  │ → CompilationUnit 리스트        │                         │
│  └────────────────┬────────────────┘                         │
│                   │ compilationUnits: List<CompilationUnit>   │
│                   ▼                                          │
│  ┌─────────────────────────────────┐                         │
│  │ Step 3: 인터페이스 구현체 매핑   │                         │
│  │ JdbcInterfaceResolver           │                         │
│  │ + Entry Class Overrides         │                         │
│  │ → 인터페이스 → 클래스 매핑       │                         │
│  └────────────────┬────────────────┘                         │
│                   │ implementors: Map<String, ClassDecl>      │
│                   ▼                                          │
│  ┌─────────────────────────────────┐                         │
│  │ Step 4: 메서드별 구현 상태 분석  │                          │
│  │ ImplementationDetector          │                         │
│  │ → 메서드 본문 AST 분석           │                         │
│  └────────────────┬────────────────┘                         │
│                   │ interfaceResults: List<InterfaceResult>   │
│                   ▼                                          │
│  ┌─────────────────────────────────┐                         │
│  │ AnalysisReport 생성              │                         │
│  └─────────────────────────────────┘                         │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│ dispatchOutputs() — 결과 출력                                 │
│  console → ConsoleReporter (터미널 텍스트)                    │
│  json:<path> → JsonReporter (JSON 파일)                      │
│  html:<path> → HtmlReporter (Chart.js 시각화 HTML)           │
└──────────────────────────────────────────────────────────────┘
```

### 파이프라인의 핵심 원리

이 프로그램의 분석 원리를 한 문장으로 요약하면:

> **"JDBC 스펙 YAML에 정의된 메서드 시그니처 목록을 기준으로, 드라이버 소스 코드의 AST에서 해당 메서드를 찾아 본문을 분석하여 실제 구현 여부를 판정한다."**

실행 파일이 아닌 **소스 코드**를 분석하는 정적 분석(static analysis) 방식이다. 바이트코드나 리플렉션이 아닌, JavaParser 라이브러리를 사용한 **AST 기반 분석**이다.

---

## 5. Step 1: JDBC 스펙 로딩

> **담당 클래스:** `JdbcSpecLoader` (`com.jdbcchecker.spec`)
> **소스:** `app/src/main/kotlin/com/jdbcchecker/spec/JdbcSpecLoader.kt`

### 5.1 YAML 스펙 파일 구조

JDBC 스펙은 인터페이스별 YAML 파일로 정의되어 있다. 각 파일은 해당 인터페이스에 속한 모든 메서드의 시그니처와 도입 버전을 담고 있다.

```yaml
# 파일: resources/jdbc-spec/java.sql.Connection.yaml
interfaceName: java.sql.Connection
since: "1.0"
methodCount: 58
methods:
  - name: createStatement
    params: []
    returns: Statement
    since: "1.0"
  - name: createStatement
    params: [int, int]
    returns: Statement
    since: "2.0"
  - name: prepareStatement
    params: [String]
    returns: PreparedStatement
    since: "1.0"
  - name: setSchema
    params: [String]
    returns: void
    since: "4.1"
  # ... 이하 생략
```

각 메서드 항목의 의미:

| 필드 | 설명 | 예시 |
|------|------|------|
| `name` | 메서드 이름 | `createStatement` |
| `params` | 파라미터 타입 목록 (순서대로) | `[int, int]` |
| `returns` | 반환 타입 | `Statement` |
| `since` | 해당 메서드가 도입된 JDBC 버전 | `"2.0"` |

**오버로딩 메서드는 별도 항목**으로 기록된다. 예를 들어 `createStatement()`과 `createStatement(int, int)`는 각각 다른 항목이다.

### 5.2 로딩 과정

```
JdbcSpecLoader.loadAll()
  │
  ├── JDBC_INTERFACES 리스트 (41개 인터페이스 FQN) 순회
  │     "java.sql.Connection", "java.sql.Statement", ...
  │     "javax.sql.DataSource", "javax.sql.XAConnection", ...
  │     "javax.transaction.xa.XAResource", "javax.transaction.xa.Xid"
  │
  └── 각 인터페이스에 대해:
        loadInterface(interfaceName)
          → javaClass.getResourceAsStream("/jdbc-spec/<interfaceName>.yaml")
          → parseYaml(interfaceName, inputStream)
          → List<MethodSignature> 반환
```

`parseYaml()`은 Jackson YAML 파서로 YAML을 읽어 `MethodSignature` 객체 리스트를 생성한다:

```kotlin
data class MethodSignature(
    val interfaceName: String,       // "java.sql.Connection"
    val methodName: String,          // "createStatement"
    val parameterTypes: List<String>,// ["int", "int"]
    val returnType: String,          // "Statement"
    val jdbcVersion: JdbcVersion,    // V2_0
)
```

### 5.3 외부 스펙 디렉터리 지원

`--spec-dir` 옵션으로 외부 YAML 디렉터리를 지정하면 번들된 스펙 대신 해당 디렉터리의 파일을 읽는다. 이때 파일명이 인터페이스 FQN이 된다 (예: `java.sql.Connection.yaml`). 디렉터리가 없으면 번들 스펙으로 자동 fallback된다.

---

## 6. Step 2: 소스 코드 파싱

> **담당 클래스:** `SourceParser` (`com.jdbcchecker.parser`)
> **소스:** `app/src/main/kotlin/com/jdbcchecker/parser/SourceParser.kt`

### 6.1 소스 해석 과정

분석 대상 소스 코드는 두 가지 방법으로 제공된다:

**로컬 경로:**
```bash
jdbc-checker analyze /path/to/driver-source --source-subdir src/main/java
```

**Git URL:**
```bash
jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git --source-subdir src/jdbc
```

Git URL의 경우 `GitCloneService`가 `git clone --depth 1`으로 shallow clone을 수행하여 임시 디렉터리에 소스를 받아온다. `--branch` 옵션으로 특정 브랜치/태그를 지정할 수 있다.

`--source-subdir`는 **복수 지정**이 가능하다. MySQL처럼 멀티모듈 구조의 드라이버에서 필요하다:

```bash
jdbc-checker analyze https://github.com/mysql/mysql-connector-j.git \
  --source-subdir src/main/user-api/java \
  --source-subdir src/main/user-impl/java
```

`SourceResolver`가 basePath에 각 subdir를 결합하여 `List<Path>`를 반환한다. subdir를 지정하지 않으면 basePath 자체가 단일 경로로 사용된다.

### 6.2 JavaParser와 Symbol Solver

소스 파싱에는 [JavaParser](https://javaparser.org/) 라이브러리를 사용한다. JavaParser는 Java 소스 코드를 **AST(Abstract Syntax Tree, 추상 구문 트리)** 로 변환하는 라이브러리이다.

```kotlin
class SourceParser(private val sourcePaths: List<Path>) {

    private fun configureSolver() {
        val typeSolver = CombinedTypeSolver().apply {
            add(ReflectionTypeSolver())       // JDK 표준 라이브러리 타입 해석
            sourcePaths.forEach { path ->
                add(JavaParserTypeSolver(path)) // 프로젝트 소스 타입 해석
            }
        }
        val symbolSolver = JavaSymbolSolver(typeSolver)
        StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver)
    }
}
```

**Symbol Solver가 필요한 이유:**

단순 파싱만으로는 `implements Connection`이라는 코드에서 `Connection`이 `java.sql.Connection`인지 `com.custom.Connection`인지 판별할 수 없다. Symbol Solver는 두 가지 타입 해석기를 결합한다:

| 타입 해석기 | 역할 |
|-----------|------|
| `ReflectionTypeSolver` | JDK 표준 라이브러리 타입 해석 (java.sql.*, javax.sql.* 등) |
| `JavaParserTypeSolver` | 프로젝트 소스 경로 내의 타입 해석 (드라이버 내부 클래스/인터페이스) |

이 두 해석기의 조합으로 **상속 체인을 완전히 추적**할 수 있다:
```
CUBRIDConnection implements Connection
                           ↓ (ReflectionTypeSolver)
                   java.sql.Connection ← JDBC 인터페이스임!
```

### 6.3 파싱 결과

`parseAll()`은 지정된 소스 경로 아래의 모든 `.java` 파일을 재귀적으로 탐색하여 각각을 `CompilationUnit`(컴파일 단위)으로 변환한다. `CompilationUnit`은 하나의 Java 파일을 나타내는 AST 루트 노드이다.

파싱 실패 시(문법 오류 등) 해당 파일은 경고를 출력하고 건너뛴다.

---

## 7. Step 3: JDBC 인터페이스 구현체 매핑

> **담당 클래스:** `JdbcInterfaceResolver` (`com.jdbcchecker.resolver`)
> **소스:** `app/src/main/kotlin/com/jdbcchecker/resolver/JdbcInterfaceResolver.kt`

### 7.1 이 단계가 해결하는 문제

JDBC 스펙은 **인터페이스** 단위이다 (예: `java.sql.Connection`의 메서드 58개). 그런데 드라이버 소스에는 이 인터페이스를 구현하는 **클래스**가 있다 (예: `CUBRIDConnection`).

이 단계의 목표는:
```
java.sql.Connection      → CUBRIDConnection
java.sql.Statement       → CUBRIDStatement
java.sql.PreparedStatement → CUBRIDPreparedStatement
java.sql.ResultSet       → CUBRIDResultSet
...
```

이 매핑을 **자동으로** 찾아내는 것이다.

### 7.2 자동 감지 알고리즘

#### Phase 1: 모든 JDBC 구현체 찾기 (`findJdbcImplementors`)

파싱된 모든 CompilationUnit에서 클래스 선언(인터페이스 제외)을 추출한 후, 각 클래스가 어떤 JDBC 인터페이스를 구현하는지 두 가지 방법으로 확인한다:

**방법 A — Direct Interface 확인:**
```java
// 클래스의 implements 절만 확인
public class CUBRIDConnection implements Connection {
//                                        ^^^^^^^^^^
//                      이 부분을 FQN으로 해석 → java.sql.Connection
}
```

`resolveDirectJdbcInterfaces(classDecl)` 함수가 클래스의 `implements` 절에 선언된 타입들을 FQN(Fully Qualified Name)으로 해석하고, `java.sql.*` / `javax.sql.*` / `javax.transaction.xa.*`에 해당하는 것만 필터링한다.

FQN 해석에 실패하면 `SIMPLE_TO_FQN` 맵을 fallback으로 사용한다:
```kotlin
"Connection" to "java.sql.Connection",
"Statement" to "java.sql.Statement",
"XAResource" to "javax.transaction.xa.XAResource",
...
```

**방법 B — Transitive Interface 확인 (전체 상속 트리):**
```java
// ConnectionImpl이 직접 implements한 것은 JdbcConnection이지만,
// JdbcConnection이 java.sql.Connection을 extends한다
public class ConnectionImpl implements JdbcConnection { ... }
public interface JdbcConnection extends java.sql.Connection { ... }
```

`resolveAllJdbcInterfaces(classDecl)` 함수는 JavaParser의 `getAllAncestors()`를 호출하여 **클래스의 모든 조상(부모 클래스 + 구현 인터페이스)**을 재귀적으로 탐색한다.

이 방법은 Symbol Solver가 상속 체인의 모든 타입을 해석할 수 있을 때만 동작한다. **파싱 범위에 중간 인터페이스 소스가 없으면 예외가 발생**하며, 그때는 방법 A(direct)로 fallback한다.

각 클래스에 대해 `ImplementorInfo(classDecl, isDirect)` 형태로 기록한다. `isDirect`는 해당 인터페이스가 클래스의 `implements` 절에 직접 선언되었는지 여부이다.

#### Phase 2: 최적 구현체 선택 (`selectBestImplementor`)

하나의 JDBC 인터페이스에 대해 여러 구현 클래스가 발견될 수 있다. 예를 들어:

```
java.sql.Connection 구현체 후보:
  - CUBRIDConnection (직접 implements, 메서드 47개)
  - CUBRIDConnectionWrapperXA (직접 implements, 메서드 12개)
  - CUBRIDConnectionPooling (직접 implements, 메서드 8개)
```

이때 **우선순위 기반 휴리스틱**으로 최적 구현체를 선택한다:

```
우선순위 1: Direct implementor를 선호
  → implements 절에 직접 선언된 클래스가 transitive보다 우선

우선순위 2: "Clean" 이름을 선호
  → 클래스명에 아래 패턴이 없는 것을 우선
     - wrapper, pooling, proxy, adapter, delegate
  → 이유: 이런 클래스는 보통 진짜 구현을 감싸는 래퍼이지 주 구현체가 아님

우선순위 3: 메서드 수가 많은 것을 선호
  → 가장 풍부한 구현을 가진 클래스가 주 구현체일 가능성이 높음
```

**참고:** `xa`는 래퍼 패턴에서 의도적으로 제외되었다. `MysqlXAResource`, `CUBRIDXAResource` 같은 XA 구현 클래스는 래퍼가 아닌 실제 구현체이기 때문이다.

### 7.3 Override 적용

자동 감지 결과에 사용자가 `--entry-class`로 지정한 override를 적용한다. Override는 자동 감지 결과를 **덮어쓴다**.

```kotlin
for ((jdbcInterface, classFqcn) in overrides) {
    val classDecl = findClassByFqcn(allClasses, classFqcn)
    if (classDecl != null) {
        result[jdbcInterface] = classDecl  // 기존 자동 감지 결과를 교체
    }
}
```

`findClassByFqcn()`은 클래스의 패키지명 + 단순 이름을 결합하여 파싱된 클래스 목록에서 정확히 일치하는 것을 찾는다.

### 7.4 최종 결과

이 단계의 최종 산출물:

```kotlin
Map<String, ClassOrInterfaceDeclaration>
// 예:
// "java.sql.Connection"     → CUBRIDConnection의 AST 노드
// "java.sql.Statement"      → CUBRIDStatement의 AST 노드
// "java.sql.ResultSet"      → CUBRIDResultSet의 AST 노드
// ...
```

매핑되지 않은 인터페이스(구현체가 없는 경우)는 이 맵에 포함되지 않으며, 해당 인터페이스의 모든 메서드는 `NotFound`로 처리된다.

---

## 8. Step 4: 메서드별 구현 상태 분석

> **담당 클래스:** `ImplementationDetector` (`com.jdbcchecker.detector`)
> **소스:** `app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt`

### 8.1 이 단계가 하는 일

Step 3에서 확보한 "인터페이스 → 클래스" 매핑을 기반으로, 스펙에 정의된 **각 메서드**가 해당 클래스에서 **어떻게 구현되어 있는지**를 하나하나 분석한다.

### 8.2 메서드 매칭

```kotlin
fun detect(specMethod: MethodSignature, classDecl: ClassOrInterfaceDeclaration): ImplementationStatus
```

매칭 조건 (모두 충족해야 함):
1. **메서드 이름 일치:** `method.nameAsString == specMethod.methodName`
2. **파라미터 수 일치:** `method.parameters.size == specMethod.parameterTypes.size`
3. **파라미터 타입 일치:** 각 파라미터의 타입 문자열을 비교

타입 비교는 유연하게 처리한다:
```kotlin
// 아래 세 경우 중 하나라도 true이면 일치로 판정
paramType == specType                    // 정확히 같음: "String" == "String"
paramType.endsWith(".$specType")         // FQN → 단순명: "java.lang.String" → "String"
specType.endsWith(".$paramType")         // 단순명 → FQN: "String" → "java.lang.String"
```

### 8.3 상속 체인 탐색

클래스 자체에서 메서드를 찾지 못하면 **부모 클래스**로 올라가며 검색한다.

```
CUBRIDPreparedStatement extends CUBRIDStatement extends ...
        │                           │
        │ setString() 찾기 →       여기에 없으면
        │                           │
        └───────────────────────────→ 부모 클래스에서 계속 검색
```

이를 위해 `registerCompilationUnits()`로 모든 파싱된 클래스를 `parentClassRegistry`에 등록해둔다:

```kotlin
// simpleName → ClassOrInterfaceDeclaration
parentClassRegistry["CUBRIDStatement"] = <CUBRIDStatement AST 노드>
parentClassRegistry["CUBRIDConnection"] = <CUBRIDConnection AST 노드>
```

`searchParentClasses()`는 클래스의 `extends` 절에서 부모 클래스명을 추출하고, 이 레지스트리에서 부모 클래스를 찾아 **재귀적으로** 탐색한다. 소스에 포함되지 않은 부모 클래스(예: JDK 표준 라이브러리 클래스)에 도달하면 탐색을 종료한다.

### 8.4 메서드 본문 분석 (analyzeMethodBody)

메서드를 찾았다면, 그 **본문(body)의 AST를 분석**하여 구현 상태를 판정한다. 이것이 이 도구의 핵심 분석 로직이다.

```
analyzeMethodBody(method)
│
├── body가 없음?
│   └── → NotFound
│
├── statements가 비어있음? (빈 메서드 본문: { })
│   └── → ReturnsDefault
│
├── statement가 정확히 1개?
│   │
│   ├── throw문인 경우:
│   │   ├── UnsupportedOperationException → ThrowsUnsupported
│   │   ├── SQLException                  → ThrowsSqlException
│   │   └── 기타 예외                      → ThrowsUnsupported
│   │
│   └── return문인 경우:
│       ├── return null/0/false/""  → ReturnsDefault
│       ├── return someMethod()     → Delegates (위임)
│       └── return <기타 표현식>     → FullyImplemented
│
└── statement가 2개 이상 (다중 문장)?
    │
    ├── 본문에 "UnsupportedOperationException" 또는
    │   "SQLException" + "not supported" 문자열이 포함?
    │   └── → Partial (일부만 구현)
    │
    └── 위 패턴이 없음?
        └── → FullyImplemented (완전 구현)
```

### 8.5 기본값 판정 (isDefaultValue)

`return` 문의 반환값이 "기본값"인지 판정하는 기준:

| 표현식 | 판정 |
|--------|------|
| `null` | 기본값 |
| `false` | 기본값 |
| `0` (int) | 기본값 |
| `0L` (long) | 기본값 |
| `0.0` (double) | 기본값 |
| `""` (빈 문자열) | 기본값 |
| 그 외 | 기본값 아님 |

`true`, `1`, `"some string"` 등은 의미 있는 반환으로 간주하여 FullyImplemented로 분류된다.

### 8.6 분석 예시

```java
// 예시 1: FullyImplemented
public Statement createStatement() throws SQLException {
    checkIsOpen();
    CUBRIDStatement stmt = new CUBRIDStatement(this, ...);
    addStatement(stmt);
    return stmt;
}
// → 다중 문장, UnsupportedOperationException 없음 → FullyImplemented

// 예시 2: ThrowsUnsupported
public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    throw new UnsupportedOperationException("Not supported");
}
// → 단일 throw, UnsupportedOperationException → ThrowsUnsupported

// 예시 3: ReturnsDefault
public String getSchema() throws SQLException {
    return null;
}
// → 단일 return null → ReturnsDefault

// 예시 4: Delegates
public void close() throws SQLException {
    return inner.close();
}
// → 단일 return + 메서드 호출 → Delegates

// 예시 5: Partial
public void setSchema(String schema) throws SQLException {
    if (schema != null) {
        executeQuery("SET SCHEMA " + schema);
    } else {
        throw new UnsupportedOperationException("null schema not supported");
    }
}
// → 다중 문장 + UnsupportedOperationException 포함 → Partial
```

---

## 9. 구현율 계산 방식

### 9.1 인터페이스별 구현율

각 인터페이스에 대해 메서드별 분석 결과를 집계한다:

```kotlin
// InterfaceResult에서 계산
val coveragePercent: Double
    get() = if (total > 0) implemented.toDouble() / total * 100 else 0.0
```

여기서 `implemented`는 `ImplementationStatus.isImplemented()`가 `true`인 메서드 수이다:

```kotlin
fun isImplemented(): Boolean = when (this) {
    is FullyImplemented -> true
    is Delegates -> true
    is Partial -> true
    else -> false
}
```

즉, **FullyImplemented + Delegates + Partial**이 "구현됨"으로 계산된다.

### 9.2 전체 구현율

```kotlin
// AnalysisReport에서 계산
val overallCoveragePercent: Double
    get() = if (totalMethods > 0)
        totalImplemented.toDouble() / totalMethods * 100 else 0.0
```

모든 인터페이스의 구현된 메서드 합계를 전체 스펙 메서드 합계로 나눈다.

### 9.3 JDBC 버전별 구현율

스펙 메서드를 JDBC 버전별로 그룹화하여 각 버전의 구현율을 별도로 계산한다:

```kotlin
val versionBreakdown: Map<JdbcVersion, VersionCoverage>
    get() = interfaces.flatMap { it.methods }
        .groupBy { it.specMethod.jdbcVersion }
        .mapValues { (_, methods) ->
            VersionCoverage(
                total = methods.size,
                implemented = methods.count { it.status.isImplemented() },
                stub = methods.count { it.status.isStub() },
                notFound = methods.count { it.status == ImplementationStatus.NotFound },
            )
        }
```

이를 통해 "이 드라이버는 JDBC 1.0은 95% 구현했지만 JDBC 4.3은 0%"와 같은 버전별 분석이 가능하다.

### 9.4 Level 2 상태 분포

7가지 세부 상태의 분포도 계산한다:

```kotlin
val statusDistribution: Map<String, Int>
    get() = interfaces.flatMap { it.methods }
        .groupBy { it.status.toLevel2Label() }
        .mapValues { it.value.size }
```

결과 예시:
```
Fully Implemented     474  (61.5%)
Throws Unsupported    101  (13.1%)
Delegates              76  ( 9.9%)
Not Found              69  ( 8.9%)
Returns Default        50  ( 6.5%)
Throws SQLException     1  ( 0.1%)
```

---

## 10. Entry Class Override 메커니즘

### 10.1 왜 필요한가?

자동 감지가 실패하는 대표적인 경우가 있다:

```java
// MySQL Connector/J의 경우
public class ConnectionImpl implements JdbcConnection { ... }
//                                      ↑
//                         java.sql.Connection이 아닌 내부 인터페이스

public interface JdbcConnection extends java.sql.Connection { ... }
//                                       ↑
//                         java.sql.Connection을 확장하지만,
//                         이 인터페이스 소스가 파싱 범위에 없으면?
```

파싱 범위(`--source-subdir`)에 `JdbcConnection` 소스가 없으면:
1. `getAllAncestors()` 호출 시 예외 발생
2. Fallback: direct `implements`만 확인
3. `JdbcConnection`은 `java.sql.*`가 아니므로 무시
4. **결과: `java.sql.Connection` 매핑 실패 → 모든 Connection 메서드가 NotFound**

### 10.2 두 가지 Override 형식

**형식 1: Explicit (명시적 매핑)**
```bash
--entry-class java.sql.Connection=com.mysql.cj.jdbc.ConnectionImpl
```
- `=`를 기준으로 좌측이 JDBC 인터페이스, 우측이 구현 클래스
- **상속 체인 해석 없이 강제 매핑**
- 가장 확실한 방법

**형식 2: Hint (자동 감지 힌트)**
```bash
--entry-class com.mysql.cj.jdbc.StatementImpl
```
- 클래스 FQN만 지정
- 해당 클래스의 CompilationUnit을 단독으로 `JdbcInterfaceResolver.resolve()`에 전달
- 자동 감지가 성공하면 매핑 추가
- **상속 체인이 파싱 범위 내에 있어야 동작**

### 10.3 Override 처리 흐름

```
entryClasses 리스트
   │
   ├── '=' 포함 → explicitOverrides (Map<String, String>)
   │     "java.sql.Connection" → "com.mysql.cj.jdbc.ConnectionImpl"
   │
   └── '=' 미포함 → hintClasses (List<String>)
         resolveHintOverrides()로 자동 감지 시도
         성공 시 hintOverrides에 추가
         실패 시 경고 메시지 출력

allOverrides = hintOverrides + explicitOverrides
                                ↑ explicit이 hint를 덮어씀 (+ 연산은 뒤가 우선)

JdbcInterfaceResolver.resolve(compilationUnits, allOverrides)
   → 자동 감지 결과에 allOverrides를 적용
```

### 10.4 실전 사용 예시

```bash
# MySQL: ConnectionImpl과 ResultSetImpl은 transitive implements이므로 explicit 필요
jdbc-checker analyze https://github.com/mysql/mysql-connector-j.git \
  --source-subdir src/main/user-api/java \
  --source-subdir src/main/user-impl/java \
  --entry-class java.sql.Connection=com.mysql.cj.jdbc.ConnectionImpl \
  --entry-class java.sql.Statement=com.mysql.cj.jdbc.StatementImpl \
  --entry-class java.sql.ResultSet=com.mysql.cj.jdbc.result.ResultSetImpl
```

---

## 11. 스펙 추출기 (SpecExtractor)

> **담당 클래스:** `SpecExtractor`, `JdkVersionMapping`, `YamlSpecWriter`
> **패키지:** `com.jdbcchecker.spec.extractor`

### 11.1 목적

JDK 소스 코드에서 JDBC 인터페이스의 메서드를 추출하여 YAML 스펙 파일을 생성한다. 새로운 JDBC 버전이 나올 때 스펙을 업데이트하는 용도이다.

### 11.2 사용법

```bash
jdbc-checker extract-spec \
  --jdk-source /path/to/jdk-src \
  --output-dir ./new-spec
```

### 11.3 JDK 소스 레이아웃 감지

JDK 소스는 버전에 따라 디렉터리 구조가 다르다. `SpecExtractor`는 두 가지 레이아웃을 자동 감지한다:

**레이아웃 A: 모듈 내부 (`src.zip` 해제 결과)**
```
jdkSourceRoot/
├── java.sql/              ← java.sql 모듈
│   ├── java/sql/          ← java.sql 패키지
│   └── javax/sql/         ← javax.sql 패키지 (같은 모듈)
└── java.transaction.xa/   ← java.transaction.xa 모듈
    └── javax/transaction/xa/
```

**레이아웃 B: 직접 패키지**
```
jdkSourceRoot/
├── java/sql/
├── javax/sql/
└── javax/transaction/xa/  (별도 모듈에서)
```

`resolveScanDirectories()` 함수가 각 패키지(`java/sql`, `javax/sql`, `javax/transaction/xa`)를 두 레이아웃 모두에서 찾아본다.

### 11.4 메서드 추출 과정

1. 각 패키지 디렉터리에서 `.java` 파일을 탐색
2. JavaParser로 파싱하여 인터페이스 선언을 찾음
3. 인터페이스의 모든 메서드 선언을 추출
4. 각 메서드의 `@since` Javadoc 태그를 파싱하여 JDBC 버전을 결정

### 11.5 @since → JDBC 버전 매핑

JDK 소스의 `@since` 태그는 **Java 버전**으로 기술되지만, 이 도구에서는 **JDBC 버전**이 필요하다. `JdkVersionMapping`이 이 변환을 담당한다:

| @since (Java) | JDBC 버전 | 비고 |
|--------------|-----------|------|
| 1.1 | V1_0 | JDBC 1.0 |
| 1.2 | V2_0 | JDBC 2.0 |
| 1.4 | V3_0 | JDBC 3.0 |
| 1.6 / 6 | V4_0 | JDBC 4.0 |
| 1.7 / 7 | V4_1 | JDBC 4.1 |
| 1.8 / 8 | V4_2 | JDBC 4.2 |
| 9 ~ 23 | V4_3 | JDBC 4.3 |
| 24 ~ 25 | V4_4 | JDBC 4.4 |
| 26 이상 | V4_5 | JDBC 4.5 |

`@since` 태그가 없는 메서드는 인터페이스 자체의 도입 버전으로 fallback한다:

```kotlin
// 인터페이스별 기본 JDBC 버전
"java.sql.Connection" → V1_0    // JDBC 1.0부터 존재
"javax.sql.DataSource" → V2_0   // JDBC 2.0부터 존재
"javax.sql.XAConnection" → V2_0
"javax.transaction.xa.XAResource" → V2_0
```

### 11.6 YAML 출력

`YamlSpecWriter`가 추출된 메서드를 인터페이스별 YAML 파일로 출력한다. 파일명은 인터페이스 FQN이다 (예: `java.sql.Connection.yaml`).

추가로 `_summary.yaml` 파일을 생성하여 전체 인터페이스 목록과 메서드 수 통계를 기록한다.

---

## 12. 출력 리포터

`-o` 옵션으로 출력 형식을 지정한다. 복수 지정 가능하다:

```bash
-o console             # 터미널 텍스트 출력 (기본값)
-o json:./report.json  # JSON 파일 출력
-o html:./report.html  # HTML 시각화 출력
```

### 12.1 Console Reporter

터미널에 텍스트 기반 보고서를 출력한다.

**출력 구성:**

```
======================================================================
  JDBC Compliance Report — CUBRID JDBC Driver
  Source: https://github.com/CUBRID/cubrid-jdbc.git
  Analyzed: 2026-03-23T06:00:00Z
======================================================================

  Overall Coverage: 65.2%
    [██████████████████████████░░░░░░░░░░░░░░] 65.2%

  Total: 771 methods
    Implemented: 503
    Stub:        189
    Not Found:    79

----------------------------------------------------------------------
  JDBC Version Breakdown:
----------------------------------------------------------------------
  JDBC 1.0     [████████████████░░░░] 82.6%  232/281
  JDBC 2.0     [██████████░░░░░░░░░░] 54.3%   89/164
  ...

----------------------------------------------------------------------
  Interface                           Coverage   Impl   Stub    N/A
----------------------------------------------------------------------
  Connection (CUBRIDConnection)         81.0%     47      4      7
  Statement (CUBRIDStatement)           90.7%     49      1      4
  PreparedStatement (CUBRIDPrepa...)   100.0%     58      0      0
  ...
----------------------------------------------------------------------

----------------------------------------------------------------------
  Implementation Detail (Level 2):
----------------------------------------------------------------------
    Fully Implemented          474  (61.5%)
    Throws Unsupported         101  (13.1%)
    ...

  Top Missing Methods:
    - Connection.beginRequest() (JDBC 4.3)
    - Connection.endRequest() (JDBC 4.3)
    ...
```

인터페이스 테이블에서 **구현 클래스명이 길면 말줄임(...)으로 처리**된다. 인터페이스명은 항상 완전히 표시되고, 클래스명 쪽이 잘린다.

### 12.2 JSON Reporter

분석 결과를 JSON 파일로 출력한다. CI/CD 파이프라인 통합이나 diff 비교의 기준(baseline)으로 사용된다.

```json
{
  "driverName": "CUBRID JDBC Driver",
  "sourcePath": "https://github.com/CUBRID/cubrid-jdbc.git",
  "analyzedAt": "2026-03-23T06:00:00Z",
  "interfaces": [
    {
      "interfaceName": "java.sql.Connection",
      "implementingClass": "cubrid.jdbc.driver.CUBRIDConnection",
      "methods": [
        {
          "specMethod": {
            "interfaceName": "java.sql.Connection",
            "methodName": "createStatement",
            "parameterTypes": [],
            "returnType": "Statement",
            "jdbcVersion": "V1_0"
          },
          "status": "fully_implemented",
          "implementingClass": "cubrid.jdbc.driver.CUBRIDConnection"
        }
      ]
    }
  ]
}
```

`ImplementationStatus`는 커스텀 직렬화를 통해 문자열 키로 변환된다:

| 상태 | JSON 키 |
|------|--------|
| FullyImplemented | `"fully_implemented"` |
| Delegates | `"delegates"` |
| Partial | `"partial"` |
| ThrowsUnsupported | `"throws_unsupported"` |
| ThrowsSqlException | `"throws_sql_exception"` |
| ReturnsDefault | `"returns_default"` |
| NotFound | `"not_found"` |

### 12.3 HTML Reporter

Chart.js를 활용한 인터랙티브 HTML 보고서를 생성한다. 단일 HTML 파일로, 외부 의존성 없이 브라우저에서 바로 열 수 있다 (Chart.js는 CDN으로 로드).

**포함 섹션:**
- 개요 카드 (전체 구현율, 총 메서드 수, 구현/스텁/미구현 수)
- 차트: 상태 분포 도넛 차트 + JDBC 버전별 누적 막대 차트
- 버전 분류표
- 인터페이스 요약 테이블 (커버리지 바 포함)
- 메서드 상세 (인터페이스별 접이식, 상태 배지 표시)

---

## 13. Diff와 Compare 기능

> **담당 클래스:** `DiffEngine` (`com.jdbcchecker.report`)
> **소스:** `app/src/main/kotlin/com/jdbcchecker/report/DiffEngine.kt`

### 13.1 Diff: 기준과 현재 비교

```bash
jdbc-checker diff baseline.json https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc -o console
```

`DiffEngine.computeDiff(baseline, current)` 로직:

1. baseline과 current의 모든 메서드를 `interfaceName::matchKey`로 인덱싱
2. 양쪽에 모두 존재하는 메서드: `score()` 비교
   - 점수 증가 → `IMPROVED`
   - 점수 감소 → `REGRESSED`
   - 동일 → `UNCHANGED`
3. 한쪽에만 존재하는 메서드: 없는 쪽을 `NotFound`로 처리

### 13.2 Compare: 다중 드라이버 비교

```bash
jdbc-checker compare \
  --driver-name CUBRID cubrid-jdbc/ \
  --driver-name MySQL mysql-connector-j/ \
  --driver-name PostgreSQL pgjdbc/ \
  -o html:./comparison.html
```

`DiffEngine.computeComparison(reports)` 로직:

1. 모든 드라이버의 모든 메서드를 `MethodKey(interfaceName, matchKey)`로 유니온
2. 각 메서드에 대해 각 드라이버의 상태를 맵으로 구성
3. 드라이버에 해당 메서드가 없으면 `NotFound`로 채움

---

## 14. 데이터 모델

### 14.1 핵심 모델 관계

```
AnalysisReport
├── driverName: String
├── sourcePath: String
├── analyzedAt: Instant
└── interfaces: List<InterfaceResult>
    ├── interfaceName: String         // "java.sql.Connection"
    ├── implementingClass: String?    // "cubrid.jdbc.driver.CUBRIDConnection"
    └── methods: List<MethodResult>
        ├── specMethod: MethodSignature
        │   ├── interfaceName: String
        │   ├── methodName: String
        │   ├── parameterTypes: List<String>
        │   ├── returnType: String
        │   └── jdbcVersion: JdbcVersion
        ├── status: ImplementationStatus  // 7가지 중 하나
        └── implementingClass: String?
```

### 14.2 JdbcVersion 열거형

```kotlin
enum class JdbcVersion(val label: String, val javaVersion: String) {
    V1_0("1.0", "JDK 1.1"),
    V2_0("2.0", "JDK 1.2"),
    V3_0("3.0", "JDK 1.4"),
    V4_0("4.0", "Java 6"),
    V4_1("4.1", "Java 7"),
    V4_2("4.2", "Java 8"),
    V4_3("4.3", "Java 9"),
    V4_4("4.4", "Java 24"),
    V4_5("4.5", "Java 26"),
}
```

### 14.3 ImplementationStatus 계층

```
ImplementationStatus (sealed interface)
├── NotFound              → Level1.NOT_FOUND
├── ThrowsUnsupported     → Level1.STUB
├── ThrowsSqlException    → Level1.STUB
├── ReturnsDefault        → Level1.STUB
├── Delegates             → Level1.IMPLEMENTED
├── Partial               → Level1.IMPLEMENTED
└── FullyImplemented      → Level1.IMPLEMENTED
```

---

## 15. 빌드 및 의존성

### 15.1 주요 의존 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| **JavaParser** (javaparser-symbol-solver) | Java 소스 → AST 변환, 타입 해석 |
| **Picocli** | CLI 명령어 파싱, 하위 명령 라우팅 |
| **Jackson** (YAML + Kotlin) | YAML 스펙 파일 파싱, JSON 직렬화/역직렬화 |
| **kotlinx-html** | HTML 보고서 DSL 생성 |
| **JUnit 5 + AssertJ** | 단위 테스트 |

### 15.2 빌드 산출물

```bash
# 래퍼 스크립트 포함 배포판 (권장)
./gradlew installDist
# → app/build/install/jdbc-checker/
#     bin/jdbc-checker        ← 실행 스크립트
#     lib/*.jar               ← 의존성 JAR 모음

# Fat JAR (단일 JAR, 모든 의존성 포함)
./gradlew fatJar
# → app/build/libs/jdbc-checker-1.0.0-all.jar

# 배포용 아카이브
./gradlew distTar distZip
# → app/build/distributions/jdbc-checker.tar
# → app/build/distributions/jdbc-checker.zip
```

### 15.3 Java 타겟

Java 21 toolchain이 설정되어 있어 JDK 21 이상이 필요하다.

---

## 16. 알려진 제약사항과 한계

### 16.1 정적 분석의 한계

이 도구는 **소스 코드 정적 분석**이므로 다음을 감지하지 못한다:

- **런타임에 동적으로 생성되는 메서드** (리플렉션, 프록시 등)
- **바이트코드 조작으로 추가된 메서드** (Lombok, ASM 등)
- **Native 메서드의 구현 품질** (JNI로 구현된 메서드는 빈 본문으로 보임)

### 16.2 Transitive implements 해석 실패

파싱 범위에 중간 인터페이스/클래스 소스가 없으면 상속 체인 추적이 실패한다. 이 경우:
- 자동 감지가 direct `implements`로 fallback
- `--entry-class` explicit 형식으로 해결 가능

대표적인 사례:
- MySQL `ConnectionImpl → JdbcConnection → java.sql.Connection` (3단계 체인)
- `core-impl` 디렉터리가 파싱 범위 밖일 때 실패

### 16.3 메서드 본문 분석의 근사치 특성

`analyzeMethodBody()`는 **휴리스틱**에 기반한다:

- 다중 문장 메서드에서 단순 문자열 매칭(`"UnsupportedOperationException" in it`)을 사용
- 조건문 분기의 도달 가능성(reachability)은 분석하지 않음
- `throw new SQLFeatureNotSupportedException()`도 `ThrowsUnsupported`로 분류되어야 하나, 현재는 문자열에 "UnsupportedOperationException"이 포함된 경우만 감지

이로 인해 소수의 메서드가 실제와 다르게 분류될 수 있다. 그러나 **수백~천 개 메서드의 전체 구현율을 계산하는 목적에서는 충분히 정확하다.**

### 16.4 드라이버별 소스 구조 차이

각 JDBC 드라이버는 소스 구조가 다르므로 `--source-subdir`와 `--entry-class`를 적절히 지정해야 한다:

| 드라이버 | source-subdir | entry-class 필요 여부 |
|---------|--------------|---------------------|
| CUBRID | `src/jdbc` | 불필요 (직접 implements) |
| PostgreSQL | `pgjdbc/src/main/java` | 불필요 |
| MS SQL Server | `src/main/java` | 불필요 |
| MariaDB | `src/main/java` | 불필요 |
| MySQL | `src/main/user-api/java` + `src/main/user-impl/java` | **필요** (transitive chain) |

---

## 부록: 용어 정리

| 용어 | 설명 |
|------|------|
| **AST** | Abstract Syntax Tree. 소스 코드를 트리 구조로 표현한 것 |
| **CompilationUnit** | JavaParser에서 하나의 Java 소스 파일을 나타내는 AST 루트 노드 |
| **ClassOrInterfaceDeclaration** | JavaParser에서 클래스 또는 인터페이스 선언을 나타내는 AST 노드 |
| **Symbol Solver** | 타입 참조를 FQN으로 해석하는 JavaParser 컴포넌트 |
| **FQN** | Fully Qualified Name. 패키지 경로를 포함한 전체 이름 (예: `java.sql.Connection`) |
| **Transitive implements** | A가 B를 구현하고, B가 C를 extends하면, A가 C를 간접(transitive) 구현함 |
| **shallow clone** | Git 히스토리 없이 최신 커밋만 복제 (`git clone --depth 1`) |
| **Level 1** | 3단계 분류: NOT_FOUND / STUB / IMPLEMENTED |
| **Level 2** | 7단계 상세 분류: NotFound / ThrowsUnsupported / ThrowsSqlException / ReturnsDefault / Delegates / Partial / FullyImplemented |
| **baseline** | diff 비교의 기준이 되는 이전 분석 결과 (JSON) |
