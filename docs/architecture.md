# 내부 구조 및 분석 원리

이 문서는 JDBC Compliance Checker의 내부 동작 원리, 분석 파이프라인, 구현 상태 분류 기준을 설명합니다.

## 전체 동작 흐름

```
┌─────────────┐     extract-spec     ┌──────────────┐
│ JDK 소스     │ ──────────────────→  │ YAML 스펙 파일 │
│ (java/sql/) │                      │ (번들 포함)    │
└─────────────┘                      └──────┬───────┘
                                            │
┌─────────────┐     JavaParser       ┌──────▼───────┐     report      ┌────────────┐
│ JDBC 드라이버 │ ──────────────────→  │  분석 엔진    │ ─────────────→  │ Console    │
│ 소스 코드    │     AST 파싱         │ (비교/매칭)   │                 │ JSON       │
└─────────────┘                      └──────────────┘                 │ HTML       │
                                                                      └────────────┘
```

## 분석 파이프라인

`analyze` 커맨드 실행 시 내부적으로 4단계 파이프라인이 순차 실행됩니다:

### Step 1: JDBC 스펙 로딩

번들 YAML 파일(또는 `--spec-dir`로 지정된 외부 디렉터리)에서 JDBC 인터페이스별 메서드 시그니처를 로딩합니다.

- 19개 핵심 인터페이스, 771개 메서드
- 각 메서드에 JDBC 버전 (`since`) 정보 포함
- 스펙 상세 구조는 [JDBC 스펙 관리 가이드](jdbc-spec-guide.md) 참고

### Step 2: 소스 코드 파싱

JavaParser + Symbol Solver를 사용하여 JDBC 드라이버 소스를 AST(Abstract Syntax Tree)로 파싱합니다.

- 지정된 경로 하위의 모든 `.java` 파일을 재귀적으로 탐색
- Symbol Solver가 타입 해석 및 상속 관계 추적을 수행
- 파싱된 CompilationUnit들을 메모리에 보관

### Step 3: 구현 클래스 자동 탐색

파싱된 소스에서 각 JDBC 인터페이스를 구현한 클래스를 자동으로 찾습니다. 탐색 전략은 아래 [구현 클래스 자동 탐색](#구현-클래스-자동-탐색) 섹션에서 상세히 설명합니다.

### Step 4: 구현 상태 탐지

스펙의 각 메서드에 대해 구현 클래스의 소스 코드를 분석하여 7단계 상태를 판정합니다. 판정 기준은 아래 [구현 상태 분류](#구현-상태-분류) 섹션에서 상세히 설명합니다.

---

## 구현 상태 분류

각 JDBC 메서드의 구현 상태를 메서드 본문(body) 분석을 통해 7단계로 분류합니다.

| 상태 | 분류 | 설명 |
|------|------|------|
| `Fully Implemented` | Implemented | 완전한 구현 |
| `Partial` | Implemented | 일부 분기에서만 구현 (나머지는 예외/기본값) |
| `Delegates` | Implemented | 다른 메서드에 위임만 수행 |
| `Returns Default` | Stub | `null`, `0`, `false`, `""` 등 기본값만 반환 |
| `Throws SQLException` | Stub | `SQLException("Not supported")` 등 예외 발생 |
| `Throws Unsupported` | Stub | `UnsupportedOperationException` 예외 발생 |
| `Not Found` | Not Found | 드라이버 소스에 해당 메서드 없음 |

리포트 상단의 요약(Implemented / Stub / Not Found)은 위 7단계를 3개 그룹으로 합산한 값입니다.

### 판정 로직 상세

#### Not Found

스펙에 정의된 메서드 시그니처(이름 + 파라미터 타입)가 구현 클래스와 그 부모 클래스 체인 어디에도 존재하지 않는 경우입니다.

#### Throws Unsupported

메서드 본문이 단일 `throw` 문이고, `UnsupportedOperationException`을 던지는 경우입니다.

```java
public Struct createStruct(String typeName, Object[] attributes) {
    throw new UnsupportedOperationException("Not supported");
}
```

#### Throws SQLException

메서드 본문이 단일 `throw` 문이고, `SQLException` (또는 하위 클래스)을 던지면서 메시지에 "not supported", "not implemented" 등이 포함된 경우입니다.

```java
public void setSchema(String schema) throws SQLException {
    throw new SQLException("Not supported");
}
```

#### Returns Default

메서드 본문이 단일 `return` 문이고, 반환값이 기본값(`null`, `0`, `false`, `""`, 빈 컬렉션)인 경우입니다.

```java
public String getSchema() throws SQLException {
    return null;
}
```

#### Delegates

메서드 본문이 단일 문장이고, 다른 메서드를 호출하여 그 결과를 반환하거나 그대로 위임하는 경우입니다.

```java
public ResultSet executeQuery(String sql) throws SQLException {
    return executeQuery(sql, false);
}
```

#### Partial

메서드에 실제 구현 로직이 있지만, 일부 분기에서 예외를 던지거나 기본값을 반환하는 경우입니다. 예를 들어, 특정 타입만 처리하고 나머지는 `UnsupportedOperationException`을 던지는 패턴입니다.

#### Fully Implemented

위의 어떤 패턴에도 해당하지 않으며, 실질적인 구현 로직이 포함된 메서드입니다.

### 부모 클래스 체인 탐색

메서드가 현재 클래스에 없으면, 부모 클래스 체인을 따라 올라가며 탐색합니다.

```
CUBRIDPreparedStatement
  └── extends CUBRIDStatement          ← 여기에서 메서드를 찾을 수 있음
        └── implements Statement
```

`ImplementationDetector`는 `registerCompilationUnits()`를 통해 모든 파싱된 클래스의 상속 관계를 사전에 등록하여, 파일 간 부모 클래스 추적이 가능합니다.

---

## 구현 클래스 자동 탐색

분석 시 각 JDBC 인터페이스의 구현 클래스를 자동으로 탐색합니다.

### 탐색 전략

1. **직접 `implements` 선언** — `implements Connection`을 직접 선언한 클래스를 우선 선택
2. **래퍼 패턴 필터링** — 클래스명에 아래 패턴이 포함된 클래스 제외:
   - `Wrapper`, `XA`, `Pooling`, `Proxy`, `Adapter`, `Delegate`
3. **메서드 수 기준** — 동점 시 JDBC 메서드를 가장 많이 보유한 클래스 선택

### 탐색 예시

CUBRID JDBC의 경우 `Connection` 인터페이스를 구현하는 클래스가 여러 개 있습니다:

| 클래스 | `implements Connection` 직접 선언 | 래퍼 패턴 | 메서드 수 |
|--------|----------------------------------|-----------|-----------|
| `CUBRIDConnection` | O | X | 많음 |
| `CUBRIDConnectionWrapperXA` | X (상속) | **XA** | 적음 |

→ `CUBRIDConnection`이 선택됩니다.

### 수동 지정

자동 탐색이 부정확한 경우 `--entry-class` 옵션으로 수동 지정할 수 있습니다:

```bash
jdbc-checker analyze ./src/jdbc \
  --entry-class com.example.MyConnection \
  --entry-class com.example.MyStatement
```

---

## JDBC 스펙 범위

JDK 21 소스에서 추출한 `java.sql.*` 및 `javax.sql.*` 인터페이스의 공개 메서드를 기준으로 합니다.

### 지원 JDBC 버전

| JDBC 버전 | Java 버전 | 메서드 수 |
|-----------|-----------|-----------|
| JDBC 1.0 | JDK 1.1 | 281 |
| JDBC 2.0 | JDK 1.2 | 164 |
| JDBC 3.0 | JDK 1.4 | 133 |
| JDBC 4.0 | Java 6 | 139 |
| JDBC 4.1 | Java 7 | 15 |
| JDBC 4.2 | Java 8 | 25 |
| JDBC 4.3 | Java 9 | 14 |
| **합계** | | **771** |

### 분석 대상 인터페이스 (19개)

`Connection`, `Statement`, `PreparedStatement`, `CallableStatement`, `ResultSet`, `ResultSetMetaData`, `DatabaseMetaData`, `Driver`, `Blob`, `Clob`, `SQLXML`, `Array`, `Ref`, `Struct`, `ParameterMetaData`, `DataSource`, `ConnectionPoolDataSource`, `CommonDataSource`, `Wrapper`

---

## 프로젝트 구조

```
jdbc-compliance-checker/
├── app/src/main/kotlin/com/jdbcchecker/
│   ├── cli/          # picocli CLI 진입점, 소스 리졸버
│   ├── model/        # 도메인 모델: MethodSignature, AnalysisResult 등
│   ├── spec/         # JDBC 스펙 YAML 로딩
│   │   └── extractor/   # JDK 소스 → YAML 추출기
│   ├── parser/       # JavaParser 기반 소스 파싱
│   ├── resolver/     # 상속 체인 해석, 인터페이스 매핑
│   ├── detector/     # 구현 수준 탐지 (7단계)
│   ├── git/          # Git 클론 서비스
│   └── report/       # 리포트 생성
│       ├── console/     # 콘솔 리포트
│       ├── json/        # JSON 리포트
│       └── html/        # HTML 리포트 (kotlinx.html + Chart.js)
├── app/src/main/resources/jdbc-spec/   # 번들 YAML 스펙 데이터
└── jdk-sources/      # 스펙 추출용 JDK 21 소스
```

---

## 기술 스택

| 구분 | 기술 | 사용 목적 |
|------|------|-----------|
| 언어 | Kotlin | Java 생태계 + 간결한 문법 |
| 빌드 | Gradle (Kotlin DSL) | 빌드 및 의존성 관리 |
| Java 파서 | JavaParser + Symbol Solver | 소스 코드 AST 파싱 및 타입 해석 |
| CLI | picocli | 커맨드라인 인터페이스 |
| 스펙 데이터 | Jackson YAML | JDBC 스펙 YAML 직렬화/역직렬화 |
| JSON 리포트 | Jackson Kotlin + JSR310 | JSON 출력 및 `java.time.Instant` 처리 |
| HTML 리포트 | kotlinx.html + Chart.js | 타입 세이프 HTML 생성 + 차트 시각화 |

---

## 알려진 제한 사항

| 항목 | 설명 |
|------|------|
| **Java 소스만 지원** | Kotlin이나 다른 JVM 언어로 작성된 드라이버는 분석할 수 없음 |
| **정적 분석 한계** | 런타임 동적 디스패치, 리플렉션 기반 구현은 감지하지 못함 |
| **`--source-subdir` 공통 적용** | `compare`에서 Git URL을 여러 개 사용할 때 서브디렉터리가 동일하게 적용됨. 다른 서브디렉터리가 필요하면 JSON으로 미리 분석 후 비교 |
| **`--branch` 공통 적용** | `compare`에서 모든 Git URL에 동일한 브랜치가 적용됨 |
| **네트워크 의존** | HTML 리포트의 Chart.js는 CDN에서 로드하므로 오프라인에서는 차트가 표시되지 않음 |
