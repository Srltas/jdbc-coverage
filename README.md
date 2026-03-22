# JDBC Compliance Checker

JDBC 드라이버의 **소스 코드**를 정적 분석하여 JDBC 스펙 준수율을 자동으로 측정하는 CLI 도구입니다.

JAR 파일이 아닌 **Java 소스 코드**를 직접 파싱하여, 각 메서드가 실제로 어떻게 구현되어 있는지를 7단계로 분류합니다. 로컬 소스 경로뿐 아니라 **Git URL을 직접 입력**하면 자동으로 클론하여 분석합니다.

## 주요 기능

- **JDBC 스펙 자동 추출** — JDK 소스의 `@since` 태그를 파싱하여 JDBC 1.0 ~ 4.3의 771개 메서드를 버전별로 분류
- **소스 코드 정적 분석** — JavaParser + Symbol Solver를 사용하여 상속 체인까지 추적하며 구현 상태 탐지
- **7단계 구현 상태 분류** — 단순 존재 여부가 아닌, 메서드 본문의 실제 내용을 분석
- **다양한 리포트 출력** — 콘솔, JSON, HTML (Chart.js 차트 포함) 동시 출력 지원
- **시점 간 변경 추적** — 이전 분석 결과와 비교하여 개선/퇴보된 메서드 확인
- **드라이버 간 비교** — 여러 JDBC 드라이버를 나란히 비교
- **Git URL 지원** — Git 리포지토리 URL을 입력하면 자동으로 shallow clone 후 분석

## 요구 사항

- **JDK 21** 이상
- **Git** (Git URL 클론 기능 사용 시)

## 빌드

```bash
git clone https://github.com/CUBRID/jdbc-compliance-checker.git
cd jdbc-compliance-checker
./gradlew fatJar
```

빌드 결과물: `app/build/libs/jdbc-checker-1.0.0-all.jar`

## 실행 방법

### 래퍼 스크립트 사용 (권장)

fat JAR과 `jdbc-checker` 스크립트를 같은 디렉터리에 배치합니다:

```bash
cp app/build/libs/jdbc-checker-1.0.0-all.jar .
./jdbc-checker <command> [options]
```

### JAR 직접 실행

```bash
java -jar jdbc-checker-1.0.0-all.jar <command> [options]
```

### Gradle로 실행 (개발 중)

```bash
./gradlew :app:run --args="<command> [options]"
```

---

## 커맨드

### `analyze` — 드라이버 소스 분석

JDBC 드라이버 소스 코드의 스펙 준수율을 분석합니다.

```
jdbc-checker analyze <source> [options]
```

#### 인자

| 인자 | 설명 |
|------|------|
| `<source>` | **(필수)** JDBC 드라이버 소스 경로 또는 Git URL |

#### 옵션

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--output <format>` | `-o` | 출력 형식 (여러 번 지정 가능) | `console` |
| `--driver-name <name>` | `-n` | 리포트에 표시할 드라이버 이름 | 자동 감지 |
| `--branch <ref>` | `-b` | Git 클론 시 브랜치 또는 태그 | 기본 브랜치 |
| `--source-subdir <path>` | | 리포지토리 내 소스 서브디렉터리 | 루트 |
| `--entry-class <fqcn>` | | 구현 클래스 수동 지정 (여러 번 지정 가능) | 자동 탐색 |
| `--spec-dir <path>` | `-s` | 외부 JDBC 스펙 YAML 디렉터리 | 번들 스펙 |

#### 출력 형식 (`-o`)

| 형식 | 값 | 설명 |
|------|------|------|
| 콘솔 | `console` | 터미널에 테이블 형태로 출력 |
| JSON | `json:<파일경로>` | 구조화된 JSON 파일 생성 |
| HTML | `html:<파일경로>` | Chart.js 차트 포함 단일 HTML 파일 생성 |

여러 형식을 동시에 지정할 수 있습니다.

#### 사용 예시

```bash
# 로컬 소스 분석
./jdbc-checker analyze ./src/jdbc

# Git URL로 분석
./jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc

# 특정 브랜치 분석
./jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc \
  --branch develop

# 콘솔 + JSON + HTML 동시 출력
./jdbc-checker analyze ./src/jdbc \
  -o console \
  -o json:./report.json \
  -o html:./report.html

# 드라이버 이름 직접 지정
./jdbc-checker analyze ./src/jdbc -n "My JDBC Driver"
```

#### 출력 예시 (콘솔)

```
======================================================================
  JDBC Compliance Report — CUBRID JDBC
  Source: https://github.com/CUBRID/cubrid-jdbc.git
  Analyzed: 2026-03-22T22:46:45.079948Z
======================================================================

  Overall Coverage: 62.3%
    [████████████████████████░░░░░░░░░░░░░░░░] 62.3%

  Total: 771 methods
    Implemented: 480
    Stub:        205
    Not Found:   86

----------------------------------------------------------------------
  JDBC Version Breakdown:
----------------------------------------------------------------------
  JDBC 1.0     [███████████████████░] 97.5%  274/281
  JDBC 2.0     [████████████████░░░░] 80.5%  132/164
  JDBC 3.0     [████████░░░░░░░░░░░░] 40.6%   54/133
  JDBC 4.0     [██░░░░░░░░░░░░░░░░░░] 14.4%   20/139
  JDBC 4.1     [░░░░░░░░░░░░░░░░░░░░] 0.0%    0/ 15
  JDBC 4.2     [░░░░░░░░░░░░░░░░░░░░] 0.0%    0/ 25
  JDBC 4.3     [░░░░░░░░░░░░░░░░░░░░] 0.0%    0/ 14

----------------------------------------------------------------------
  Interface                           Coverage   Impl   Stub    N/A
----------------------------------------------------------------------
  ResultSetMetaData (CUBRIDResult...)   100.0%     21      0      0
  DatabaseMetaData (CUBRIDDatabas...)    92.7%    164     10      3
  Driver (CUBRIDDriver)                  85.7%      6      1      0
  Statement (CUBRIDStatement)            68.5%     37      5     12
  ResultSet (CUBRIDResultSet)            64.8%    125     60      8
  Connection (CUBRIDConnection)          58.6%     34     17      7
  ...

----------------------------------------------------------------------
  Implementation Detail (Level 2):
----------------------------------------------------------------------
    Fully Implemented          442  (57.3%)
    Throws Unsupported         205  (26.6%)
    Not Found                   86  (11.2%)
    Delegates                   34  ( 4.4%)
    Partial                      4  ( 0.5%)
```

---

### `diff` — 시점 간 변경 비교

이전 분석 결과(JSON)와 현재 소스(또는 다른 JSON)를 비교하여 변경된 메서드를 추적합니다.

```
jdbc-checker diff <baseline.json> <current> [options]
```

#### 인자

| 인자 | 설명 |
|------|------|
| `<baseline.json>` | **(필수)** 기준이 되는 이전 분석 JSON 파일 |
| `<current>` | **(필수)** 비교 대상: 로컬 소스 경로, Git URL, 또는 JSON 파일 |

#### 옵션

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--output <format>` | `-o` | 출력 형식: `console`, `json:<path>` | `console` |
| `--driver-name <name>` | `-n` | 현재 분석의 드라이버 이름 | 자동 감지 |
| `--branch <ref>` | `-b` | Git URL 사용 시 브랜치 | 기본 브랜치 |
| `--source-subdir <path>` | | Git URL 사용 시 소스 서브디렉터리 | 루트 |
| `--spec-dir <path>` | `-s` | 외부 JDBC 스펙 YAML 디렉터리 | 번들 스펙 |

#### 사용 예시

```bash
# 저장된 JSON vs 현재 로컬 소스 비교
./jdbc-checker diff ./baseline.json ./src/jdbc

# JSON vs JSON 비교 (재분석 없이 빠름)
./jdbc-checker diff ./v1.json ./v2.json

# JSON vs Git URL 비교
./jdbc-checker diff ./baseline.json \
  https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc

# 결과를 JSON으로 저장
./jdbc-checker diff ./v1.json ./v2.json \
  -o console -o json:./diff-result.json
```

#### 출력 예시

```
======================================================================
  JDBC Compliance Diff
  Baseline : CUBRID JDBC  (2026-03-01)
  Current  : CUBRID JDBC  (2026-03-22)
======================================================================
  Coverage : 58.6%  →  62.3%  (+3.7%)
  Improved  : 28 methods
  Regressed : 0 methods
  Unchanged : 743 methods

----------------------------------------------------------------------
  Improved Methods:
----------------------------------------------------------------------
  + Connection.setSchema(String)  (JDBC 4.1)
      Not Found  →  Fully Implemented
  + Statement.getLargeUpdateCount()  (JDBC 4.2)
      Throws Unsupported  →  Fully Implemented
  ...
```

---

### `compare` — 드라이버 간 비교

2개 이상의 JDBC 드라이버를 나란히 비교합니다.

```
jdbc-checker compare <source1> <source2> [source3...] [options]
```

#### 인자

| 인자 | 설명 |
|------|------|
| `<sources>` | **(필수, 2개 이상)** 로컬 소스 경로, Git URL, 또는 JSON 파일 (혼합 가능) |

#### 옵션

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--driver-name <name>` | `-n` | 드라이버 이름 (소스 순서대로, 여러 번 지정) | 자동 감지 |
| `--output <format>` | `-o` | 출력 형식: `console`, `json:<path>` | `console` |
| `--branch <ref>` | `-b` | Git URL 사용 시 브랜치 (모든 Git URL에 공통 적용) | 기본 브랜치 |
| `--source-subdir <path>` | | Git URL 사용 시 소스 서브디렉터리 (공통 적용) | 루트 |
| `--spec-dir <path>` | `-s` | 외부 JDBC 스펙 YAML 디렉터리 | 번들 스펙 |

#### 사용 예시

```bash
# 두 로컬 소스 비교
./jdbc-checker compare ./cubrid/src ./pgsql/src \
  -n CUBRID -n PostgreSQL

# JSON + Git URL 혼합 비교
./jdbc-checker compare ./cubrid.json \
  https://github.com/pgjdbc/pgjdbc.git \
  --source-subdir pgjdbc/src/main/java \
  -n CUBRID -n PostgreSQL

# 결과를 JSON으로 저장
./jdbc-checker compare ./a.json ./b.json \
  -o console -o json:./comparison.json
```

---

### `extract-spec` — JDK 소스에서 JDBC 스펙 추출

JDK 소스 코드의 `@since` Javadoc 태그를 파싱하여 JDBC 인터페이스별 메서드 목록을 YAML로 추출합니다.

```
jdbc-checker extract-spec <jdk-source-root> [options]
```

#### 인자

| 인자 | 설명 |
|------|------|
| `<jdk-source-root>` | **(필수)** `java/sql/`과 `javax/sql/`이 포함된 JDK 소스 루트 디렉터리 |

#### 옵션

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--output <dir>` | `-o` | YAML 파일 출력 디렉터리 | `jdbc-spec/` |
| `--interfaces <list>` | | 추출할 인터페이스 목록 (콤마 구분) | 전체 |

#### 사용 예시

```bash
# 전체 JDBC 인터페이스 추출
./jdbc-checker extract-spec ./jdk-sources -o ./my-spec

# 특정 인터페이스만 추출
./jdbc-checker extract-spec ./jdk-sources \
  --interfaces Connection,Statement,ResultSet
```

> **참고**: 프로젝트에 JDK 21 기준 스펙 YAML이 이미 번들링되어 있습니다 (771개 메서드, 19개 인터페이스). 이 커맨드는 새로운 JDK 버전에 맞춰 스펙을 갱신할 때 사용합니다.

---

## 소스 입력 형식

모든 커맨드에서 소스 인자는 **로컬 경로**와 **Git URL**을 모두 지원합니다.

| 입력 형식 | 판별 기준 | 예시 |
|-----------|-----------|------|
| 로컬 경로 | 일반 파일 시스템 경로 | `./src/jdbc`, `/home/user/driver/src` |
| Git URL (HTTPS) | `https://`로 시작 | `https://github.com/CUBRID/cubrid-jdbc.git` |
| Git URL (SSH) | `git@`로 시작 | `git@github.com:CUBRID/cubrid-jdbc.git` |
| JSON 파일 | `.json`으로 끝남 | `./baseline.json`, `/tmp/report.json` |

### Git URL 동작 방식

- **Shallow clone** (depth 1)으로 최소한의 데이터만 다운로드
- 분석 완료 후 임시 디렉터리 **자동 삭제**
- `--branch` 옵션으로 특정 브랜치 또는 태그 지정 가능
- `--source-subdir` 옵션으로 리포지토리 내 소스 위치 지정

### 드라이버 이름 자동 감지

경로 또는 URL에서 드라이버 이름을 자동으로 감지합니다:

| 키워드 | 감지 결과 |
|--------|-----------|
| `cubrid` | CUBRID JDBC |
| `mysql` | MySQL Connector/J |
| `mariadb` | MariaDB Connector/J |
| `postgresql`, `pgjdbc` | PostgreSQL JDBC |
| 기타 | 디렉터리명 또는 리포지토리명 |

`-n` 옵션으로 수동 지정하면 자동 감지를 덮어씁니다.

---

## 구현 상태 분류

각 JDBC 메서드의 구현 상태를 메서드 본문 분석을 통해 7단계로 분류합니다.

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

## 구현 클래스 자동 탐색

분석 시 각 JDBC 인터페이스의 구현 클래스를 자동으로 탐색합니다.

### 탐색 전략

1. **직접 `implements` 선언** — `implements Connection`을 직접 선언한 클래스를 우선 선택
2. **래퍼 패턴 필터링** — 클래스명에 `Wrapper`, `XA`, `Pooling`, `Proxy`, `Adapter`, `Delegate`가 포함된 클래스 제외
3. **메서드 수 기준** — 동점 시 JDBC 메서드를 가장 많이 보유한 클래스 선택

자동 탐색이 부정확한 경우 `--entry-class` 옵션으로 수동 지정할 수 있습니다:

```bash
./jdbc-checker analyze ./src/jdbc \
  --entry-class com.example.MyConnection \
  --entry-class com.example.MyStatement
```

---

## 일반적인 워크플로우

### 1. 현재 상태 분석 및 저장

```bash
./jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc \
  -o console \
  -o json:./cubrid-baseline.json \
  -o html:./cubrid-report.html
```

### 2. HTML 리포트 확인

```bash
open ./cubrid-report.html   # macOS
xdg-open ./cubrid-report.html   # Linux
```

### 3. 소스 수정 후 변경 추적

```bash
./jdbc-checker diff ./cubrid-baseline.json ./src/jdbc
```

### 4. 다른 드라이버와 비교

```bash
./jdbc-checker compare \
  ./cubrid-baseline.json \
  https://github.com/pgjdbc/pgjdbc.git \
  --source-subdir pgjdbc/src/main/java \
  -n CUBRID -n PostgreSQL
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

## 라이선스

Apache License 2.0
