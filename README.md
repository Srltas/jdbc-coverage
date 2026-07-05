# JDBC Compliance Checker

JDBC 드라이버의 **소스 코드**를 정적 분석하여 JDBC API 구현 비율을 측정하는 CLI 도구입니다.
CUBRID JDBC의 스펙 확장(→ JDBC 4.2, 이후 4.3+) 진행률을 매일 추적하고, 다른 오픈소스
드라이버(MySQL/MariaDB/PostgreSQL/MSSQL)와 비교하는 것이 목적입니다.

- 메서드 본문을 분석해 7단계로 분류하고(구현/스텁/부재), 스텁(`throw UnsupportedOperationException`,
  기본값만 반환 등)은 구현으로 치지 않습니다. 분류 규칙: [docs/ANALYSIS_RULES.md](docs/ANALYSIS_RULES.md)
- 스펙은 JDK 26 기준 34개 인터페이스 **889 메서드로 동결**(`spec-1`)되어 있습니다. 스펙이나
  분류 규칙이 바뀌면 히스토리 전체의 의미가 변하므로, 변경 시 spec 버전을 올려야 합니다.
- 모든 스냅샷에 spec 버전·도구 버전·소스 git commit이 각인됩니다.

## 빌드 (JDK 21+)

```bash
./gradlew :app:installDist
export PATH="$PWD/app/build/install/jdbc-checker/bin:$PATH"
```

## 사용법

```bash
# 분석 (소스 디렉터리는 패키지 루트여야 함 — 예: src/main/java)
jdbc-checker analyze ~/src/cubrid-jdbc/src/jdbc -n "CUBRID JDBC"

# JSON 저장 + JDBC 4.2까지만 측정
jdbc-checker analyze ~/src/cubrid-jdbc/src/jdbc --jdbc-version 4.2 -o console -o json:report.json

# 히스토리 기록 (전일 대비 델타 계산 + history/*.jsonl 추가 + latest/*.json 갱신)
jdbc-checker analyze ~/src/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" --history ./reports

# 트렌드 대시보드 렌더링 (자기완결 HTML)
jdbc-checker dashboard ./reports -o ./reports/index.html
```

| `analyze` 옵션 | 설명 | 기본값 |
|------|------|--------|
| `<source-dir>...` | 패키지 루트 디렉터리 (1개 이상) | (필수) |
| `-o console\|json:<path>` | 출력 (반복 가능) | `console` |
| `-n <name>` | 드라이버 표시 이름 | 경로에서 자동 감지 |
| `--profile <name>` | 번들 프로파일 (`cubrid` `mysql` `mariadb` `pgjdbc` `mssql`) | 패키지명 자동 감지 |
| `--entry-class <iface>=<class>` | 구현 클래스 수동 고정 (반복 가능) | 프로파일/자동 탐지 |
| `--jdbc-version <ver>` | 스펙 상한 (예: `4.2`) | 전체 스펙 |
| `--history <dir>` | 히스토리 디렉터리에 기록 | — |

**드라이버 프로파일**: 구현 클래스 자동 탐지가 어려운 드라이버(예: MySQL)의 인터페이스→클래스
매핑과 드라이버 관용구(예: MSSQL의 throw 헬퍼)를 담습니다. 패키지명으로 자동 적용되며,
프로파일이 고정한 클래스가 소스에 없으면 **분석이 실패**합니다(숫자가 조용히 바뀌는 것 방지 —
드라이버 리팩터링 시 `app/src/main/resources/profiles/<name>.yaml`을 갱신하세요).

## 매일 자동 실행 (GitHub Actions)

`.github/workflows/daily.yml`이 매일 5개 드라이버를 분석해 `gh-pages` 브랜치에 히스토리를
쌓고 대시보드를 게시합니다. 수동 실행: Actions 탭 → daily-coverage → Run workflow.

## 스펙 관리

번들 스펙(`app/src/main/resources/jdbc-spec/*.yaml`)은 JDK 소스의 `@since` 태그에서
추출되었고 JDK 26 리플렉션 대조로 검증되었습니다(누락 0). 재추출 도구(`extract-spec`)는
v2.0.0에서 제거되었습니다 — 필요 시 git 히스토리의 `spec/extractor` 패키지를 참조하세요.
스펙을 갱신하면 `JdbcSpecLoader.SPEC_VERSION`을 반드시 올리세요.

## 라이선스

Apache License 2.0
