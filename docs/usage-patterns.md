# 실전 사용 패턴

이 문서는 JDBC Compliance Checker를 실전에서 효과적으로 활용하는 방법을 설명합니다. 분석 결과 파일 관리, 버전별 추적, CI/CD 연동 등 다양한 사용 패턴을 다룹니다.

## 분석 결과 파일 관리

### JSON 파일

JSON 출력은 **사용자가 `-o json:<경로>`로 지정한 경로**에 생성됩니다. 자동으로 생성되는 디렉터리나 기본 출력 경로는 없으며, 경로는 전적으로 사용자가 결정합니다.

```bash
# 절대 경로
jdbc-checker analyze ./src/jdbc -o json:/tmp/report.json

# 상대 경로 (현재 디렉터리 기준)
jdbc-checker analyze ./src/jdbc -o json:./report.json

# 하위 디렉터리 (디렉터리가 이미 존재해야 함)
mkdir -p ./reports
jdbc-checker analyze ./src/jdbc -o json:./reports/cubrid-2026-03.json
```

JSON 파일의 용도:
- `diff` 커맨드의 입력 (베이스라인 또는 비교 대상)
- `compare` 커맨드의 입력
- 외부 도구에서 데이터 가공 (`jq`, Python 등)
- CI에서 결과 보관 및 자동 비교

### HTML 파일

HTML 출력도 **사용자가 `-o html:<경로>`로 지정한 경로**에 생성됩니다.

```bash
jdbc-checker analyze ./src/jdbc -o html:./reports/cubrid-report.html

# 브라우저에서 열기
open ./reports/cubrid-report.html       # macOS
xdg-open ./reports/cubrid-report.html   # Linux
```

HTML 파일은 **단일 자급식(self-contained) 파일**입니다. CSS가 내장되어 있고, Chart.js만 CDN에서 로드합니다. 파일 하나만 공유하면 누구든 브라우저에서 열어볼 수 있습니다.

### 동시 출력

여러 형식을 **동시에** 출력할 수 있습니다:

```bash
jdbc-checker analyze ./src/jdbc \
  -o console \
  -o json:./reports/cubrid.json \
  -o html:./reports/cubrid.html
```

---

## 버전별 JSON 관리 패턴

JDBC 드라이버의 개선 과정을 추적하려면 JSON 파일을 버전별로 보관하는 것이 유용합니다.

### 패턴 1: 날짜 기반 관리

정기적으로(주간/월간) 분석하여 시간 경과에 따른 변화를 추적합니다:

```bash
mkdir -p ./reports/cubrid

# 월별 분석
jdbc-checker analyze ./src/jdbc \
  -o json:./reports/cubrid/2026-01.json \
  -o html:./reports/cubrid/2026-01.html

jdbc-checker analyze ./src/jdbc \
  -o json:./reports/cubrid/2026-03.json \
  -o html:./reports/cubrid/2026-03.html

# 1월 → 3월 변경 추적
jdbc-checker diff \
  ./reports/cubrid/2026-01.json \
  ./reports/cubrid/2026-03.json
```

### 패턴 2: 릴리스 버전 기반 관리

드라이버의 릴리스 버전별로 분석 결과를 보관합니다:

```bash
mkdir -p ./reports/cubrid

# v11.2 릴리스 분석
jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --branch release/11.2 \
  --source-subdir src/jdbc \
  -o json:./reports/cubrid/v11.2.json

# v11.3 릴리스 분석
jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --branch release/11.3 \
  --source-subdir src/jdbc \
  -o json:./reports/cubrid/v11.3.json

# 릴리스 간 비교
jdbc-checker diff \
  ./reports/cubrid/v11.2.json \
  ./reports/cubrid/v11.3.json \
  -o console \
  -o json:./reports/cubrid/diff-v11.2-v11.3.json
```

### 패턴 3: 다중 드라이버 비교 관리

여러 JDBC 드라이버를 분석하고 JSON으로 보관하면, 재분석 없이 빠르게 비교할 수 있습니다:

```bash
mkdir -p ./reports/{cubrid,pgsql,mysql}

# 각 드라이버 분석 (1회만 수행)
jdbc-checker analyze https://github.com/CUBRID/cubrid-jdbc.git \
  --source-subdir src/jdbc \
  -o json:./reports/cubrid/latest.json

jdbc-checker analyze https://github.com/pgjdbc/pgjdbc.git \
  --source-subdir pgjdbc/src/main/java \
  -o json:./reports/pgsql/latest.json

# JSON 파일로 빠른 비교 (재분석 불필요, 즉시 실행)
jdbc-checker compare \
  ./reports/cubrid/latest.json \
  ./reports/pgsql/latest.json \
  -n CUBRID -n PostgreSQL
```

> **팁**: Git URL을 직접 `compare`에 넣으면 각 드라이버를 매번 클론+파싱해야 합니다. JSON으로 미리 저장해두면 `compare`/`diff` 실행이 즉시 완료됩니다.

### 패턴 4: CI/CD 파이프라인

CI에서 매 빌드마다 분석을 수행하고, 이전 베이스라인과 비교하는 스크립트 예시입니다:

```bash
#!/bin/bash
# ci-jdbc-check.sh

REPORT_DIR="./reports/$(date +%Y-%m-%d)"
mkdir -p "$REPORT_DIR"

# 현재 소스 분석
jdbc-checker analyze ./src/jdbc \
  -o json:"$REPORT_DIR/current.json" \
  -o html:"$REPORT_DIR/report.html"

# 베이스라인이 있으면 diff 실행
if [ -f "./reports/baseline.json" ]; then
  jdbc-checker diff \
    ./reports/baseline.json \
    "$REPORT_DIR/current.json" \
    -o console \
    -o json:"$REPORT_DIR/diff.json"
fi

# 현재를 새 베이스라인으로 갱신
cp "$REPORT_DIR/current.json" ./reports/baseline.json
```

CI 아티팩트로 `$REPORT_DIR/report.html`을 업로드하면, PR 리뷰 시 브라우저에서 바로 확인할 수 있습니다.

---

## 추천 디렉터리 구조

장기적으로 분석 결과를 관리할 때 추천하는 디렉터리 구조입니다:

```
my-project/
├── reports/                    ← JSON/HTML 보관 (Git에 포함하거나 .gitignore)
│   ├── baseline.json           ← 현재 베이스라인
│   ├── cubrid/
│   │   ├── v11.2.json
│   │   ├── v11.3.json
│   │   └── latest.html
│   └── comparison/
│       └── cubrid-vs-pgsql.json
├── custom-spec/                ← (선택) 커스텀 JDBC 스펙 YAML
│   └── *.yaml
└── jdbc-checker/               ← 도구 설치 디렉터리
    ├── bin/
    │   └── jdbc-checker
    └── lib/
        └── *.jar
```

### reports/ 디렉터리의 Git 관리

| 전략 | 장점 | 단점 |
|------|------|------|
| **Git에 포함** | 분석 이력 추적 가능, 팀 공유 용이 | 저장소 크기 증가 |
| **.gitignore** | 저장소 깔끔 | 이력 유실, CI 아티팩트로 별도 관리 필요 |

소규모 프로젝트에서는 Git에 포함하는 것이 간편합니다. JSON 파일은 수십 KB 수준이므로 저장소 크기 부담이 크지 않습니다.

---

## jq를 이용한 JSON 데이터 가공

JSON 출력은 `jq`로 자유롭게 가공할 수 있습니다:

```bash
# 전체 커버리지 확인
jq '.interfaces[] | {name: .interfaceName, class: .implementingClass}' report.json

# Not Found 메서드만 추출
jq '.interfaces[].methods[] | select(.status == "NOT_FOUND") | .specMethod.displayName' report.json

# 인터페이스별 커버리지 계산
jq '.interfaces[] | {
  interface: .interfaceName,
  total: (.methods | length),
  implemented: ([.methods[] | select(.status == "FULLY_IMPLEMENTED" or .status == "DELEGATES" or .status == "PARTIAL")] | length)
}' report.json

# JDBC 4.0+ 미구현 메서드만 추출
jq '[.interfaces[].methods[]
  | select(.status == "NOT_FOUND")
  | select(.specMethod.jdbcVersion == "V4_0" or .specMethod.jdbcVersion == "V4_1" or .specMethod.jdbcVersion == "V4_2" or .specMethod.jdbcVersion == "V4_3")
  | .specMethod.displayName]' report.json
```

---

## 드라이버별 소스 서브디렉터리 참고

주요 JDBC 드라이버의 Git 리포지토리와 소스 위치입니다:

| 드라이버 | Git URL | `--source-subdir` |
|----------|---------|-------------------|
| CUBRID | `https://github.com/CUBRID/cubrid-jdbc.git` | `src/jdbc` |
| PostgreSQL | `https://github.com/pgjdbc/pgjdbc.git` | `pgjdbc/src/main/java` |
| MySQL | `https://github.com/mysql/mysql-connector-j.git` | `src/main/core-impl/java` |
| MariaDB | `https://github.com/mariadb-corporation/mariadb-connector-j.git` | `src/main/java` |

> **참고**: 소스 구조는 버전에 따라 변경될 수 있습니다. `--source-subdir`가 맞지 않으면 "No Java source files found" 에러가 발생합니다. 이 경우 리포지토리를 직접 확인하여 `java.sql.*`을 구현한 소스 파일의 루트 경로를 찾아 지정하세요.
