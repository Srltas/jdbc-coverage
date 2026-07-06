# JDBC Coverage

JDBC 드라이버의 **소스 코드**를 정적 분석하여 JDBC API 구현 비율을 측정하는 CLI 도구입니다.
CUBRID JDBC의 스펙 확장(→ JDBC 4.2, 이후 4.3+) 진행률을 매일 추적하고, 다른 오픈소스
드라이버(MySQL/MariaDB/PostgreSQL/MSSQL)와 비교하는 것이 목적입니다.

- 메서드 본문을 분석해 7단계로 분류하고(구현/스텁/부재), 스텁(`throw UnsupportedOperationException`,
  기본값만 반환 등)은 구현으로 치지 않습니다. 분류 규칙: [docs/ANALYSIS_RULES.md](docs/ANALYSIS_RULES.md)
- 스펙은 JDK 26 기준 34개 인터페이스 **889 메서드로 동결**(`spec-2`)되어 있습니다. 대표 커버리지는
  **주력 JDBC 816개** 기준이고, **주변부 60개**·**XA/JTA 13개**는 별도 지표로 병기합니다 —
  선별·분류 기준은 [측정 스코프](#측정-스코프)를 참조하세요. 스펙이나 분류 규칙이 바뀌면
  히스토리 전체의 의미가 변하므로, 변경 시 spec 버전을 올려야 합니다.
- 모든 스냅샷에 spec 버전·도구 버전·소스 git commit이 각인됩니다.

## 빌드 (JDK 21+)

```bash
./gradlew :app:installDist
export PATH="$PWD/app/build/install/jdbc-coverage/bin:$PATH"
```

## 사용법

```bash
# 분석 (소스 디렉터리는 패키지 루트여야 함 — 예: src/main/java)
jdbc-coverage analyze ~/src/cubrid-jdbc/src/jdbc -n "CUBRID JDBC"

# JSON 저장 + JDBC 4.2까지만 측정
jdbc-coverage analyze ~/src/cubrid-jdbc/src/jdbc --jdbc-version 4.2 -o console -o json:report.json

# 히스토리 기록 (전일 대비 델타 계산 + history/*.jsonl 추가 + latest/*.json 갱신)
jdbc-coverage analyze ~/src/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" --history ./reports

# 트렌드 대시보드 렌더링 (자기완결 HTML)
jdbc-coverage dashboard ./reports -o ./reports/index.html
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

## 측정 스코프

"DB 벤더가 구현하는 JDBC API를 얼마나 구현했는가"를 재는 것이 목적이므로, 세는 메서드는
다음 **두 조건을 모두** 만족하는 인터페이스의 것으로 한정합니다.

1. **패키지**가 `java.sql` 또는 `javax.sql`일 것 (= JDBC API)
2. **구현 주체가 JDBC 드라이버**일 것 (애플리케이션·JDK·커넥션 풀/앱서버·트랜잭션 매니저가
   구현하는 인터페이스는 제외)

**기준(source of truth)**: JDK 26 (Temurin 26.0.1)의 `java.sql`+`javax.sql`. "정의된 메서드"는
각 인터페이스에 **직접 선언된(declared) public 메서드** — Javadoc의 *Method Summary*에 해당하며
상속 메서드("Methods inherited from …")는 제외합니다. 리플렉션으로 34개 인터페이스 전부 대조
검증했습니다(YAML과 1:1 일치).

### 그룹 (총 889 = JDBC 876 + XA 13)

측정 대상을 세 그룹으로 분류합니다. **주력 JDBC**(816)를 대표 커버리지로 삼고, **주변부**(60)와
**XA/JTA**(13)는 별도 지표로 병기합니다 — 핵심 완성도가 옵션·비JDBC 기능의 미구현에 희석되지
않도록.

| 그룹 | 계층 | 인터페이스 | 메서드 |
|---|---|---|---:|
| **주력 JDBC**<br>(816) | 코어 실행·메타데이터 | Driver, Connection, Statement, PreparedStatement, CallableStatement, ResultSet, DatabaseMetaData, ResultSetMetaData, ParameterMetaData, Savepoint | 704 |
|  | 데이터 타입 객체 | Blob, Clob, Array, Struct, Ref, RowId, SQLXML | 59 |
|  | 베이스 계약 | Wrapper | 2 |
|  | 커넥션 프로비저닝 | CommonDataSource, DataSource, ConnectionPoolDataSource, XADataSource, PooledConnection, XAConnection, ConnectionBuilder, ShardingKeyBuilder, PooledConnectionBuilder, XAConnectionBuilder | 51 |
| **주변부**<br>(60) | UDT 커스텀 매핑 스트림 | SQLInput, SQLOutput | 56 |
|  | 벤더 타입 마커 | SQLType | 3 |
|  | 드라이버 콜백 | DriverAction | 1 |
| **XA / JTA**<br>(13) | 분산 트랜잭션 — JDBC 아님(`javax.transaction.xa`) | XAResource, Xid | 13 |

> **주변부**는 드라이버가 구현할 수 있으나 대다수가 지원하지 않는(UDT 스트림) 옵션 API,
> **XA/JTA**는 패키지부터 `javax.transaction.xa`라 애초에 JDBC(`java.sql`/`javax.sql`)가 아닙니다.
> 그래서 헤드라인 수치에 섞지 않고 별도로 표시합니다. (`Xid`는 트랜잭션 매니저가 생성하므로
> 드라이버가 구현조차 하지 않지만, XA 표면을 함께 보기 위해 이 그룹에 포함합니다.)

### 제외 (측정하지 않음)

두 조건을 통과하지 못해 스펙 집합에서 아예 빠진 것들:

| 인터페이스 | 제외 이유 |
|---|---|
| `java.sql.SQLData` | **애플리케이션**이 구현 (UDT ↔ Java 클래스 매핑). 드라이버는 이 객체에 넘길 `SQLInput`/`SQLOutput`만 구현 |
| `javax.sql.ConnectionEventListener`, `StatementEventListener` | **커넥션 풀/앱서버**가 구현 (드라이버의 `PooledConnection`이 콜백) |
| `javax.sql.rowset.*` (RowSet, CachedRowSet …) | 클라이언트측 컨테이너 API. 5개 주요 드라이버 모두 구현 0 |
| `java.sql.NClob`, `java.sql.ShardingKey` | 고유 선언 메서드 0개 (각각 Clob 상속 / 마커) — 메서드 커버리지에 기여 없음 |
| `javax.naming.Referenceable`(JNDI), `java.io.Serializable`(직렬화) | JDBC 패키지 밖 (조건 1 탈락). DataSource 구현체가 실제로 구현하더라도 JDBC API가 아님 |

스코프(포함 인터페이스·분류·상한)를 바꾸면 히스토리 수치의 의미가 달라지므로 반드시
`JdbcSpecLoader.SPEC_VERSION`을 올리세요.

## 스펙 관리

번들 스펙(`app/src/main/resources/jdbc-spec/*.yaml`)은 JDK 소스의 `@since` 태그에서
추출되었고 JDK 26 리플렉션 대조로 검증되었습니다(누락 0). 재추출 도구(`extract-spec`)는
v2.0.0에서 제거되었습니다 — 필요 시 git 히스토리의 `spec/extractor` 패키지를 참조하세요.
스펙을 갱신하면 `JdbcSpecLoader.SPEC_VERSION`을 반드시 올리세요.

## 라이선스

Apache License 2.0
