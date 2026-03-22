---
name: analyze-driver
description: >
  Run JDBC compliance analysis on a driver source and summarize results.
  Use when the user asks to analyze a driver, check coverage, or run the tool.
  Trigger on: "분석해줘", "분석 실행", "커버리지 확인", "돌려봐", "analyze", "run analysis".
trigger: always
---

# Analyze Driver Skill

## Purpose

JDBC 드라이버 소스에 대해 분석을 실행하고, 결과를 사람이 읽기 좋게 요약한다.

## Execution Steps

### 1. Build the tool (if needed)

```bash
cd /Users/cubrid/Devel/JDBC/java-compliance-checker
./gradlew build -x test
```

### 2. Run analysis

```bash
# Local source analysis
java -jar build/libs/jdbc-compliance-checker.jar \
  --source-path /path/to/driver/src \
  --output console \
  --output json:result.json

# Common driver source paths
# CUBRID:     /Users/cubrid/Devel/JDBC/cubrid-jdbc/src/main/java
# MySQL:      src/main/user-impl/java
# PostgreSQL: pgjdbc/src/main/java
```

### 3. Summarize results

결과를 다음 형식으로 요약:

```
## JDBC Compliance Report — [Driver Name]

### Overall Coverage
- Total: XX / YYY methods (XX.X%)
- Implemented: XXX | Stub: XX | Not Found: XX

### By JDBC Version
| Version | Coverage | Implemented | Stub | Not Found |
|---------|----------|-------------|------|-----------|
| 1.0     | 95.0%    | 38/40       | 1    | 1         |
| 2.0     | 85.0%    | 51/60       | 5    | 4         |
| ...     | ...      | ...         | ...  | ...       |

### Key Gaps (Critical Missing Methods)
- Connection.createClob() — NOT_FOUND (JDBC 4.0)
- Statement.executeLargeUpdate() — STUB (JDBC 4.2)
- ...

### Recommendations
1. [JDBC version]에서 [N]개 메서드 미구현 — 우선 구현 권장
2. ...
```

### 4. Compare with previous (if available)

이전 결과 JSON이 있으면 diff 출력:

```
### Changes from Previous Analysis
- +3 methods implemented (Connection: +2, Statement: +1)
- -1 regression (ResultSet.getObject removed)
- Coverage: 72.3% → 73.8% (+1.5%)
```

## Known Driver Source Locations

| Driver | Repository | Source Root |
|--------|-----------|------------|
| CUBRID | `cubrid-jdbc` | `src/main/java` |
| MySQL | `mysql-connector-j` | `src/main/user-impl/java` |
| MariaDB | `mariadb-connector-j` | `src/main/java` |
| PostgreSQL | `pgjdbc` | `pgjdbc/src/main/java` |

## Error Handling

- 소스 경로가 잘못된 경우 → 경로 확인 안내
- 빌드 실패 → `./gradlew build` 오류 메시지 분석
- JavaParser 오류 → Symbol Solver 설정 확인, 의존성 소스 누락 여부 확인
