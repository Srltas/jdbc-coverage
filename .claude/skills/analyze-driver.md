---
name: analyze-driver
description: >
  Run JDBC API coverage analysis on a driver source and summarize results.
  Use when the user asks to analyze a driver, check coverage, or run the tool.
  Trigger on: "분석해줘", "분석 실행", "커버리지 확인", "돌려봐", "analyze", "run analysis".
trigger: always
---

# Analyze Driver Skill

## Purpose

Run JDBC API coverage analysis against a driver source directory and present
the results in a human-readable summary.

## Execution Steps

### 1. Build the tool (if needed)

```bash
cd /Users/cubrid/Devel/JDBC/java-compliance-checker
./gradlew :app:installDist
```

### 2. Run analysis

```bash
# Local source analysis (source dir must be a package root, e.g. src/main/java)
app/build/install/jdbc-coverage/bin/jdbc-coverage \
  analyze /path/to/driver/src/main/java \
  -o console \
  -o json:result.json

# Common driver source paths (package roots)
# CUBRID:     /Users/cubrid/Devel/JDBC/cubrid-jdbc/src/jdbc
# MySQL:      mysql-connector-j            (multi-module root; profile pins the impl classes)
# PostgreSQL: pgjdbc/pgjdbc/src/main/java
```

### 3. Summarize results

Present the output in the following format:

```
## JDBC API Coverage Report — [Driver Name]

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
1. N methods unimplemented in JDBC [version] — recommended to prioritize
2. ...
```

### 4. Compare with previous (if available)

If a previous result JSON exists, show the diff:

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

- Invalid source path → guide user to verify the path
- Build failure → analyze the `./gradlew build` error output
- JavaParser error → check Symbol Solver configuration and verify all source dependencies are present
