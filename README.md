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
./gradlew :app:installDist
```

빌드 결과:

```
app/build/install/jdbc-checker/
├── bin/
│   ├── jdbc-checker       ← 실행 스크립트 (Unix)
│   └── jdbc-checker.bat   ← 실행 스크립트 (Windows)
└── lib/
    └── *.jar              ← 애플리케이션 + 의존성 JAR
```

### 배포 패키지

배포용 tar/zip 파일을 생성할 수 있습니다:

```bash
./gradlew :app:distTar    # → app/build/distributions/jdbc-checker.tar
./gradlew :app:distZip    # → app/build/distributions/jdbc-checker.zip
```

압축을 풀면 `bin/jdbc-checker` 스크립트로 바로 실행 가능합니다.

### 기타 실행 방법

```bash
# fat JAR 직접 실행
./gradlew fatJar
java -jar app/build/libs/jdbc-checker-1.0.0-all.jar <command> [options]

# Gradle로 실행 (개발 중)
./gradlew :app:run --args="<command> [options]"
```

## 빠른 시작

```bash
# PATH 등록
export PATH="$PWD/app/build/install/jdbc-checker/bin:$PATH"

# CUBRID JDBC 분석 (Git URL)
jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc \
  -o console -o html:./report.html

# 로컬 소스 분석
jdbc-checker analyze ./src/jdbc -o console -o json:./report.json
```

## 커맨드

### `analyze` — 드라이버 소스 분석

JDBC 드라이버 소스 코드의 스펙 준수율을 분석합니다.

```
jdbc-checker analyze <source> [options]
```

| 인자 | 설명 |
|------|------|
| `<source>` | **(필수)** JDBC 드라이버 소스 경로 또는 Git URL |

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--output <format>` | `-o` | 출력 형식 (여러 번 지정 가능) | `console` |
| `--driver-name <name>` | `-n` | 리포트에 표시할 드라이버 이름 | 자동 감지 |
| `--branch <ref>` | `-b` | Git 클론 시 브랜치 또는 태그 | 기본 브랜치 |
| `--source-subdir <path>` | | 리포지토리 내 소스 서브디렉터리 | 루트 |
| `--entry-class <fqcn>` | | 구현 클래스 수동 지정 (여러 번 지정 가능) | 자동 탐색 |
| `--spec-dir <path>` | `-s` | 외부 JDBC 스펙 YAML 디렉터리 | 번들 스펙 |

**출력 형식** (`-o`): `console`, `json:<파일경로>`, `html:<파일경로>` — 여러 형식을 동시에 지정 가능

```bash
# 로컬 소스 분석
jdbc-checker analyze ./src/jdbc

# Git URL + 브랜치 + 다중 출력
jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc --branch develop \
  -o console -o json:./report.json -o html:./report.html
```

---

### `diff` — 시점 간 변경 비교

이전 분석 결과(JSON)와 현재 소스(또는 다른 JSON)를 비교하여 변경된 메서드를 추적합니다.

```
jdbc-checker diff <baseline.json> <current> [options]
```

| 인자 | 설명 |
|------|------|
| `<baseline.json>` | **(필수)** 기준이 되는 이전 분석 JSON 파일 |
| `<current>` | **(필수)** 비교 대상: 로컬 소스 경로, Git URL, 또는 JSON 파일 |

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--output <format>` | `-o` | 출력 형식: `console`, `json:<path>` | `console` |
| `--driver-name <name>` | `-n` | 현재 분석의 드라이버 이름 | 자동 감지 |
| `--branch <ref>` | `-b` | Git URL 사용 시 브랜치 | 기본 브랜치 |
| `--source-subdir <path>` | | Git URL 사용 시 소스 서브디렉터리 | 루트 |
| `--spec-dir <path>` | `-s` | 외부 JDBC 스펙 YAML 디렉터리 | 번들 스펙 |

```bash
# JSON vs 현재 소스
jdbc-checker diff ./baseline.json ./src/jdbc

# JSON vs JSON (재분석 없이 빠름)
jdbc-checker diff ./v1.json ./v2.json

# JSON vs Git URL
jdbc-checker diff ./baseline.json https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc
```

---

### `compare` — 드라이버 간 비교

2개 이상의 JDBC 드라이버를 나란히 비교합니다.

```
jdbc-checker compare <source1> <source2> [source3...] [options]
```

| 인자 | 설명 |
|------|------|
| `<sources>` | **(필수, 2개 이상)** 로컬 소스 경로, Git URL, 또는 JSON 파일 (혼합 가능) |

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--driver-name <name>` | `-n` | 드라이버 이름 (소스 순서대로, 여러 번 지정) | 자동 감지 |
| `--output <format>` | `-o` | 출력 형식: `console`, `json:<path>` | `console` |
| `--branch <ref>` | `-b` | Git URL 사용 시 브랜치 (모든 Git URL에 공통 적용) | 기본 브랜치 |
| `--source-subdir <path>` | | Git URL 사용 시 소스 서브디렉터리 (공통 적용) | 루트 |
| `--spec-dir <path>` | `-s` | 외부 JDBC 스펙 YAML 디렉터리 | 번들 스펙 |

```bash
# 두 로컬 소스 비교
jdbc-checker compare ./cubrid/src ./pgsql/src -n CUBRID -n PostgreSQL

# JSON 파일들로 빠른 비교
jdbc-checker compare ./cubrid.json ./pgsql.json -n CUBRID -n PostgreSQL
```

---

### `extract-spec` — JDK 소스에서 JDBC 스펙 추출

JDK 소스 코드의 `@since` Javadoc 태그를 파싱하여 JDBC 인터페이스별 메서드 목록을 YAML로 추출합니다.

```
jdbc-checker extract-spec <jdk-source-root> [options]
```

| 인자 | 설명 |
|------|------|
| `<jdk-source-root>` | **(필수)** `java/sql/`과 `javax/sql/`이 포함된 JDK 소스 루트 디렉터리 |

| 옵션 | 짧은 형태 | 설명 | 기본값 |
|------|-----------|------|--------|
| `--output <dir>` | `-o` | YAML 파일 출력 디렉터리 | `jdbc-spec/` |
| `--interfaces <list>` | | 추출할 인터페이스 목록 (콤마 구분) | 전체 |

> **참고**: 프로젝트에 JDK 21 기준 스펙이 이미 번들링되어 있습니다. 이 커맨드는 새로운 JDK 버전에 맞춰 스펙을 갱신할 때만 사용합니다. 자세한 내용은 [JDBC 스펙 관리 가이드](docs/jdbc-spec-guide.md)를 참고하세요.

---

## 소스 입력 형식

모든 커맨드에서 소스 인자는 **로컬 경로**와 **Git URL**을 모두 지원합니다.

| 입력 형식 | 판별 기준 | 예시 |
|-----------|-----------|------|
| 로컬 경로 | 일반 파일 시스템 경로 | `./src/jdbc`, `/home/user/driver/src` |
| Git URL (HTTPS) | `https://`로 시작 | `https://github.com/CUBRID/cubrid-jdbc.git` |
| Git URL (SSH) | `git@`로 시작 | `git@github.com:CUBRID/cubrid-jdbc.git` |
| JSON 파일 | `.json`으로 끝남 | `./baseline.json`, `/tmp/report.json` |

Git URL은 **shallow clone** (depth 1)으로 빠르게 클론하며, 분석 완료 후 임시 디렉터리를 **자동 삭제**합니다.

---

## 상세 문서

| 문서 | 내용 |
|------|------|
| [내부 구조 및 분석 원리](docs/architecture.md) | 동작 흐름, 구현 상태 7단계 분류, 구현 클래스 자동 탐색 전략, 기술 스택 |
| [JDBC 스펙 관리 가이드](docs/jdbc-spec-guide.md) | 번들 스펙 설명, YAML 파일 구조, JDK 소스 준비 및 스펙 재추출 방법 |
| [실전 사용 패턴](docs/usage-patterns.md) | 파일 관리, 버전별 JSON 관리, CI/CD 파이프라인, 추천 디렉터리 구조 |

## 라이선스

Apache License 2.0
