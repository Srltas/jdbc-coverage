# JDBC Compliance Checker — Project Agent Guide

## Project Identity

- **Name**: jdbc-compliance-checker
- **Language**: Kotlin (JVM 17+)
- **Build**: Gradle (Kotlin DSL)
- **Purpose**: JDBC 드라이버 소스 코드를 정적 분석하여 JDBC 스펙 준수율을 자동으로 측정하는 CLI 도구

## Core Objective

> **JDBC 드라이버의 API 구현 상태를 소스 코드 기반으로 자동 확인하는 것**
>
> 이 목적에만 집중한다. CI 게이트, 동적 분석 등 부가 기능은 핵심이 안정화된 후에 고려한다.

## Key Design Decisions

### 1. Source Input (소스 입력)
- **로컬 경로** (`--source-path ./src`)와 **Git URL** (`--git-url https://...`) 둘 다 지원
- Git 이력(커밋별 변화 추적) 분석은 v1.0 이후로 미룸
- MVP에서는 **현재 스냅샷 분석**에 집중

### 2. JDBC Spec Mapping (스펙 매핑)
- **JDK 소스(OpenJDK)에서 자동 추출** → YAML로 export → Git으로 관리
- 대상: `java.sql.*`, `javax.sql.*` 인터페이스의 모든 public 메서드
- JDBC 버전별 분류: 1.0, 2.0, 3.0, 4.0, 4.1, 4.2, 4.3
- 자동 추출기를 프로젝트 첫 번째 마일스톤으로 구현

### 3. Implementation Level Detection (구현 수준 판정)
- **Level 2 (상세 분류)** 설계, Level 1부터 점진적 구현
- Level 1 (MVP):
  - `NOT_FOUND` — 메서드 자체가 없음
  - `STUB` — throw UnsupportedOperationException / return default
  - `IMPLEMENTED` — 실제 로직 있음
- Level 2 (v0.2):
  - `NOT_FOUND`
  - `THROWS_UNSUPPORTED` — throw new UnsupportedOperationException
  - `THROWS_SQLEXCEPTION` — throw new SQLException("Not supported")
  - `RETURNS_DEFAULT` — return null / return 0 / return false
  - `DELEGATES` — 다른 메서드 호출만 함
  - `PARTIAL` — 일부 파라미터 조합만 처리
  - `FULLY_IMPLEMENTED` — 완전한 구현

### 4. Inheritance Chain Resolution (상속 체인)
- **자동 탐색**: `java.sql.*` / `javax.sql.*`를 implements한 클래스를 자동 발견
- 같은 인터페이스를 구현하는 클래스가 여럿이면, 상속 계층에서 **가장 하위(구체적인) 클래스** 기준으로 판정
- `--entry-class` 옵션으로 수동 오버라이드 가능
- JavaParser Symbol Solver로 전체 상속 체인 해석

### 5. Report Output (리포트)
- **콘솔**: 인터페이스별 커버리지 요약 + 미구현 메서드 목록
- **JSON**: CI 연동 및 데이터 분석용 구조화된 출력
- **HTML** (v0.2): kotlinx.html + Chart.js 임베드, 단일 HTML 파일
- 복수 출력 동시 지원: `--output console --output json:result.json`

### 6. CI Integration (CI 연동)
- 현재 단계에서는 **제외** — API 구현 상태 확인이라는 핵심 목적에만 집중
- JSON 출력만 잘 하면 CI workflow에서 외부 도구로 처리 가능
- `--fail-under`, PR 코멘트 등은 향후 필요 시 추가

## Tech Stack

| Category | Choice | Reason |
|---|---|---|
| Language | Kotlin | Java 생태계 그대로 + 간결한 문법 |
| Build | Gradle (Kotlin DSL) | Kotlin 프로젝트 표준 |
| Java Parser | JavaParser + Symbol Solver | 상속 체인 해석, 타입 분석 최고 성숙도 |
| CLI | picocli | JVM CLI 표준, 코드 생성 지원 |
| Spec Data | YAML (snakeyaml-engine) | 비개발자도 편집 가능, 버전 관리 용이 |
| HTML Report | kotlinx.html + Chart.js | 타입 안전 + 의존성 최소 |
| Test | JUnit 5 + AssertJ | 표준 테스트 프레임워크 |

## Roadmap

### v0.1 — MVP (Target: 1~2 weeks)
- [ ] Gradle 프로젝트 초기 구조 설정
- [ ] JDBC 스펙 자동 추출기 (OpenJDK 소스 → YAML)
- [ ] 소스 코드 정적 분석 엔진 (Level 1 판정)
- [ ] 상속 체인 자동 탐색 및 해석
- [ ] 콘솔 리포트 출력
- [ ] JSON 리포트 출력
- [ ] CUBRID JDBC 소스로 실제 분석 검증

### v0.2 — Enhanced Analysis
- [ ] Level 2 상세 판정 구현
- [ ] HTML 리포트 (kotlinx.html + Chart.js)
- [ ] 분석 결과 캐싱

### v0.3 — Comparison
- [ ] 복수 드라이버 소스 비교 분석
- [ ] 결과 diff (이전 결과와 비교)

### v1.0 — Full Feature
- [ ] JAR 분석 (Reflection 기반)
- [ ] Git URL 자동 클론 지원

### v1.5+ — Future
- [ ] 동적 분석 (실제 DB 연결)
- [ ] Git 이력 추적 (커밋별 커버리지 변화)

## Architecture Overview

```
jdbc-compliance-checker/
├── spec-extractor/        # JDBC 스펙 추출 모듈 (OpenJDK → YAML)
├── analyzer-core/         # 핵심 분석 엔진
│   ├── model/             # MethodSignature, AnalysisResult 등 도메인 모델
│   ├── parser/            # JavaParser 기반 소스 파싱
│   ├── resolver/          # 상속 체인 해석, 인터페이스 매핑
│   └── detector/          # 구현 수준 판정 (Level 1, 2)
├── report/                # 리포트 생성
│   ├── console/
│   ├── json/
│   └── html/
├── cli/                   # picocli 기반 CLI 진입점
└── jdbc-spec/             # YAML 스펙 데이터
    ├── java.sql.Connection.yaml
    ├── java.sql.Statement.yaml
    └── ...
```

## Coding Conventions

- Kotlin coding conventions: https://kotlinlang.org/docs/coding-conventions.html
- 모든 public API에 KDoc 작성
- data class로 불변 모델 설계
- sealed class/interface로 상태 표현 (ImplementationStatus 등)
- 확장 함수 적극 활용
- 테스트: Given-When-Then 패턴

## Important Constraints

1. **범용성 유지**: CUBRID 전용 로직 금지. 어떤 JDBC 드라이버 소스든 분석 가능해야 함
2. **핵심 집중**: API 구현 상태 확인이 유일한 목표. 부가 기능에 시간 쓰지 않기
3. **점진적 확장**: Level 1 → Level 2, 콘솔 → HTML 순서로. 한 번에 다 만들지 않기
4. **정확도 우선**: 속도보다 분석 정확도가 중요. 잘못된 판정은 도구 신뢰를 깨뜨림
