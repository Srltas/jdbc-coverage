# JDBC Compliance Checker 경량화 설계

- 작성일: 2026-07-05
- 상태: 사용자 승인 대기 → 승인 후 구현 계획(writing-plans) 진행

## 1. 배경과 목표

CUBRID JDBC 드라이버를 JDBC 4.2(이후 4.3+)까지 확장하는 과정에서, **API 구현 비율을 매일 자동으로 측정하고 타 오픈소스 드라이버(MySQL, MariaDB, PostgreSQL, MSSQL)와 비교**하는 것이 이 도구의 존재 이유다. 현재 도구는 기능이 넓게 퍼져 있어(커맨드 4개, analyze 옵션 9개, 메인 소스 ~4,400 LOC) 사용이 어렵고, 정작 위 목표에 필요한 세 가지 — 버전 경계 커버리지 지표, 히스토리/추이, 자동 데일리 실행 — 가 없다.

**목표: 검증된 분석 엔진은 유지하고, 기능 절반 이상을 제거하며, 데일리 자동화에 필요한 기능만 추가한다.**

## 2. 현황 분석 요약 (2026-07-05 정밀 분석)

- 실사용 워크플로우는 `analyze <src> -o json: -o html:`를 드라이버별 실행 후 날짜 디렉터리에 수동 수집하는 것뿐. `diff`/`compare`는 실사용 0회, 비교·추이는 외부 스크립트/수작업.
- 2026-05-21 검증 완료 수치(스펙 896 메서드, JDK 26 리플렉션 대조 0 누락): CUBRID 40.6%, PostgreSQL 53.3%, MariaDB 67.1%, MySQL 70.4%, MSSQL 71.7%. CUBRID는 V4.0 11.6%, V4.1/4.2 0%.
- 스텁 분류(ThrowsUnsupported/ThrowsSqlException/ReturnsDefault)와 프로파일의 entryClasses 고정은 수치 정확성의 근간 (없으면 CUBRID ~80% 과대 측정, MySQL 47.2%↔71.3% 출렁임).
- 프로파일 서브시스템·896 스펙·룰 수정이 **미커밋 상태** — 현재 HEAD 빌드는 검증 수치를 재현하지 못한다.
- 분류 규칙 변경 한 번에 CUBRID 62.3%→40.6%로 이동한 전례가 있어, 히스토리의 의미를 지키려면 스펙·규칙 동결과 스냅샷 프로버넌스가 선행 조건이다.

## 3. 사용자 확정 결정사항

| 결정 | 내용 |
|------|------|
| 실행 방식 | GitHub Actions 스케줄(cron) + 수동 트리거, 결과는 GitHub Pages 게시 |
| 확인 화면 | 매일 갱신되는 자기완결 트렌드 대시보드 HTML 한 장 |
| 접근 방식 | A안: 기존 엔진 유지 + 대수술 (재작성 아님) |
| 지표 기준 | `--jdbc-version` 기본값은 **최신 스펙 전체**(현재 4.5). 4.2 진행률은 버전별 누적 커버리지 표기로 항상 노출 |
| 스코프 정리 | SQLData(3), ConnectionEventListener(2), StatementEventListener(2) 제외 후 스펙 동결 |
| diff/compare | 완전 제거, 대시보드가 델타·비교 표시를 대신 |

## 4. CLI 표면

커맨드 2개만 남긴다.

```
jdbc-checker analyze <source-dir>... [options]
  --profile <name>               번들 프로파일 명시 (기본: 패키지명 자동감지)
  --entry-class <iface>=<class>  구현 클래스 명시 고정 (반복 가능, 명시 형식만)
  --driver-name <name>           리포트 표시 이름 (기본: 자동감지, mssql 매핑 추가)
  --jdbc-version <ver>           지표 상한 (기본: 스펙에 존재하는 최신 버전)
  --history <dir>                히스토리 디렉터리에 기록 (델타 계산 + JSONL 추가 + latest 갱신)
  -o console | json:<path>       출력 (기본: console, 반복 가능)

jdbc-checker dashboard <history-dir> -o <html-path>
```

- **히스토리 기록·델타 계산은 도구(`--history`)가 담당한다.** 워크플로우 YAML은 `analyze --history` ×5 + `dashboard` 호출만 하며, jq/셸 후처리는 없다.

- `<source-dir>`는 1개 이상의 **패키지 루트**(예: `src/main/java`)를 받는다. `--source-subdir`는 폐기. 심볼 솔버가 패키지 루트를 요구한다는 사실(수치 출렁임의 숨은 원인)을 문서화하고, 디렉터리 구조가 패키지 선언과 어긋나면 경고한다.
- 제거하는 커맨드·옵션: `diff`, `compare`, `extract-spec`(dev 스크립트로 이동), `-o html:`, Git URL 입력, `--branch`, `--source-subdir`, `--spec-dir`, `--profile-file`, `--no-profile`, `--entry-class` 힌트(FQCN 단독) 형식.

## 5. 지표 정의

- **헤드라인 % = 구현 메서드 / 스펙 전체 메서드** (기본: 최신 스펙 전체).
- **버전별 누적 커버리지를 콘솔·대시보드에 항상 표기**: 스펙에 메서드가 존재하는 각 버전 경계(현재 기준 ≤1.0 … ≤4.2, ≤4.3, ≤4.5)의 누적 % (현재 versionBreakdown은 버전별 도입 기준이라 누적치가 없음 → 신규 구현). 경계 목록은 하드코딩하지 않고 스펙의 `since` 값에서 유도한다. 당면 목표인 "4.2까지 몇 %"는 ≤4.2 줄로 확인한다.
- **구현 판정 규칙은 현행 그대로 동결**: Delegates/Partial/FullyImplemented = 구현, 스텁 3종 + NotFound = 미구현. 7단계 세부 분류는 JSON에 유지하여 메서드 단위 델타 표시에 사용한다.
- **스펙 스코프 1회 정리 후 동결**: RowSet 제거와 동일한 논리("드라이버가 구현하는 대상이 아님")로 `java.sql.SQLData`, `javax.sql.ConnectionEventListener`, `javax.sql.StatementEventListener` 제외(−7 메서드). 이후 스펙에 `specVersion`(예: `spec-1`)을 부여해 커밋하고 변경을 금지한다. 변경이 불가피하면 specVersion을 올리고 대시보드에 경계선을 표시한다.
- deprecated 메서드(예: `ResultSet.getBigDecimal(int,int)`)는 분모에 유지한다(현행 유지, 단순성 우선).

## 6. 스냅샷 프로버넌스

모든 분석 JSON에 다음을 각인한다: `specVersion`, 도구 버전, 분석 대상 소스의 git commit hash, 적용된 프로파일 이름. 목적: 히스토리에서 "드라이버가 좋아진 것"과 "도구/스펙이 바뀐 것"을 구분.

## 7. 히스토리 저장 구조 (gh-pages 브랜치)

전체 JSON은 드라이버당 ~370KB로 영구 보관 시 연 ~675MB가 되므로 두 층으로 나눈다.

```
gh-pages/
├── dashboard.html            ← dashboard 커맨드 산출물 (자기완결 HTML)
├── history/
│   └── <driver>.jsonl        ← 하루 한 줄, 영구 보관
└── latest/
    └── <driver>.json         ← 최신 전체 리포트, 매일 덮어쓰기
```

- `history/<driver>.jsonl`의 한 줄: 날짜, 헤드라인 %, 버전별 누적 %, 상태별 개수, specVersion, 도구 버전, 소스 commit hash, **전일 대비 변경 메서드 목록**(메서드명 + 상태 전이; 보통 하루 몇 건).
- 델타는 `analyze --history <dir>`가 오늘의 전체 리포트를 기존 `latest/<driver>.json`과 비교해 계산한 뒤 `latest/`를 덮어쓴다. 비교 키는 기존 DiffEngine과 동일한 `인터페이스FQN::메서드매치키`, 방향은 status score. 첫 실행(기존 파일 없음)은 델타 없이 기록하고, 같은 날 재실행 시 해당 날짜 줄을 교체한다. specVersion이 다른 latest와는 델타를 계산하지 않고 "스펙 변경"으로 기록한다.

## 8. dashboard 커맨드

`history-dir`(위 구조)를 읽어 자기완결 HTML 한 장을 생성한다. 내용:

1. **추이 차트**: 히스토리에 있는 모든 드라이버의 헤드라인 % 꺾은선 (JSONL 전체 기간)
2. **오늘의 비교표**: 드라이버별 헤드라인 % + 전일 대비 델타 + 버전별 누적 %
3. **드라이버별 상세**: 버전별 누적 커버리지와 인터페이스별 커버리지 표 — `latest/`의 모든 드라이버에 대해 생성 (특정 드라이버 하드코딩 없음)
4. **어제 대비 변경 메서드**: 드라이버별 상태 전이 목록
5. specVersion 변경 시 차트에 경계선 표시

렌더링은 자기완결 HTML: 데이터를 JSON으로 임베드하고 소량의 자체 인라인 JS로 SVG 차트를 그린다. 외부 요청(CDN, 폰트, 이미지) 0 — Pages에 올라가지만 오프라인에서도 열린다.

## 9. GitHub Actions 워크플로우

체커 리포의 `daily.yml` 하나. 트리거: `schedule`(매일) + `workflow_dispatch`.

1. 체커 checkout + JDK 21 + gradle 캐시 → `installDist`
2. 드라이버 5종 checkout — CUBRID: 개발 브랜치, 타 드라이버: default 브랜치 최신 (움직이는 타깃이지만 commit hash가 각인되므로 변동 원인 추적 가능)
3. gh-pages checkout (히스토리 디렉터리)
4. 드라이버별 `analyze <package-roots> --history <gh-pages-dir>` (경로는 워크플로우 YAML에 명시, 프로파일은 자동감지)
5. `dashboard <gh-pages-dir> -o dashboard.html` → gh-pages 커밋/푸시 → Pages 게시
6. 어느 단계든 실패 시 job 실패로 표면화 (아래 에러 처리와 연동)

## 10. 에러 처리와 결정성

cron 환경에서 조용한 실패는 히스토리에 구멍을 내므로, 관대한 현행 동작을 뒤집는다.

| 상황 | 현행 | 변경 |
|------|------|------|
| 알 수 없는 `-o` 형식 | 경고 후 exit 0 | **exit 1** |
| JSON 출력 부모 디렉터리 없음 | 분석 완료 후 크래시 | **자동 생성** |
| 프로파일 entryClass 핀이 소스에 없음 | 경고 후 휴리스틱 폴백 (숫자 조용히 변동) | **exit 1** (프로파일 갱신 강제) |
| 파일 순회 순서 | 파일시스템 순서 (타이브레이크 비결정적) | **정렬** (결정성 확보) |
| 파싱 실패 파일 | stderr 한 줄 | 리포트 본문에 눈에 띄게 표기 |

## 11. 제거 목록

| 대상 | 근거 |
|------|------|
| `diff` 서브커맨드 + DiffReporter/DiffJsonReporter (~500 LOC) | 실사용 0회, 델타는 데일리 잡+대시보드가 대체. DiffEngine의 비교 로직만 델타 계산에 재활용 |
| `compare` 서브커맨드 + ComparisonReporter | 실사용 0회, 전 소스 공통 옵션 구조라 5개 드라이버 비교에 실사용 불가 |
| `extract-spec` + extractor 서브시스템 (~630 main + 276 test LOC) | JDK 릴리스당 1회 필요. 별도 dev 스크립트/문서로 이동 |
| HtmlReporter (406 LOC) + kotlinx-html 의존성 | 대시보드가 대체. CDN Chart.js 의존 문제도 해소 |
| GitCloneService + SourceResolver의 클론 로직 + `--branch` | CI checkout이 대체 |
| `--entry-class` 힌트 형식 + resolveHintOverrides | 힌트한 클래스가 구현한 모든 인터페이스를 조용히 덮어쓰는 위험 동작 |
| `--profile-file`, `--no-profile`, `--spec-dir` | 사용자가 번들 프로파일·스펙의 유일한 관리자라 escape hatch 무용. `--spec-dir` 오타 시 번들 폴백으로 잘못된 분모 위험 |
| kapt + picocli-codegen, fatJar 태스크, snakeyaml-engine 카탈로그 항목 | 소비되지 않는 산출물·중복 런치 경로·미사용 선언 |
| 리포 내 작업 산출물 ~88MB (verification/, jdbc-api/, jdbc_api/, 0325_jdbc_api/, mysql_result/, app/report.json, jdk-sources/) | 리포 밖 아카이브 후 삭제, .gitignore 정비 |
| 문서 통합: README + docs/ANALYSIS_RULES.md 2개만 유지 | 나머지(architecture/technical-guide/usage-patterns/jdbc-spec-guide/AGENT.md)는 스펙 수·JSON 키·존재하지 않는 플래그 등 상호 모순. 필요한 내용은 README로 흡수 |

리졸버의 휴리스틱 우선순위 5–6(JDBC_INTERFACE_ANCESTORS 테이블, leaf-subclass 규칙)은 **이번에는 유지**한다. 제거하려면 먼저 mssql/mariadb 프로파일에 모호 인터페이스 핀을 보강해야 하며(순서 의존), 이는 후속 과제로 남긴다.

## 12. 유지 목록 (동결 대상)

- 파서(SourceParser) → 리졸버(JdbcInterfaceResolver) → 디텍터(ImplementationDetector) 파이프라인과 스텁 분류 규칙 전체
- 프로파일 서브시스템(자동감지 + `--profile`)과 번들 프로파일 5종의 entryClasses/stubHelpers
- 번들 스펙 YAML(스코프 정리 후 896−7=889 메서드) + JdbcSpecLoader
- ConsoleReporter, JsonReporter(+ObjectMapperFactory)
- 디텍터 테스트 31개 + 프로파일 테스트 (분류 규칙 동결 장치)
- `./gradlew :app:installDist` 단일 런치 경로

## 13. 작업 순서

1. **미커밋 검증 작업 선커밋**: 프로파일 서브시스템, 896 스펙, 룰 수정, RowSet 제거 등 현재 dirty tree를 기준선으로 커밋 (HEAD가 검증 수치를 재현하도록)
2. 스펙 스코프 정리(−3 인터페이스) + specVersion 도입 → `spec-1` 동결
3. 제거 수술 (§11)
4. 신규 기능: 버전별 누적 지표, 프로버넌스, 델타 계산, `dashboard` 커맨드, 에러 처리 강화 (§5–§8, §10)
5. 리포 청소: 산출물 아카이브·삭제, .gitignore, 문서 통합, `_summary.yaml` 수정 또는 삭제
6. GitHub Actions `daily.yml` + gh-pages 셋업, 수동 트리거로 종단 검증

각 단계 후 전체 테스트 통과 + CUBRID/MySQL 로컬 체크아웃 분석 수치가 기대값(스코프 정리분 제외하고 5/21 검증 수치와 일치)인지 확인한다.

## 14. 테스트 전략

- 기존: 디텍터 31개 + 프로파일 테스트 유지 — 수술 중 분류 규칙이 변하지 않았음을 보증하는 안전망
- 신규: 버전별 누적 계산, 프로버넌스 직렬화/역직렬화, 델타 계산(상태 전이·첫 실행·스펙 버전 불일치 시 동작), dashboard 스모크(유효 HTML + 데이터 포함), exit code 계약(잘못된 `-o`, 핀 실효), 파일 순회 결정성
- 수치 회귀 검증: 스코프 정리 직전/직후 CUBRID 분석을 돌려 예상 델타(−7 메서드 효과)만 발생했는지 확인

## 15. 범위 외 (하지 않는 것)

- 분류 규칙·휴리스틱의 정확도 개선 (동결이 목표; 개선은 specVersion/ruleVersion 상향과 함께 별도 과제)
- 리졸버 휴리스틱 5–6 제거 (프로파일 핀 보강 선행 필요, 후속 과제)
- 메일/슬랙 알림, 다중 브랜치 추적, JAR(바이트코드) 분석
