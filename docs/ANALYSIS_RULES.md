# JDBC Compliance Checker — 분석 룰셋

이 문서는 jdbc-compliance-checker 도구가 **"무엇을 측정하고 어떻게 판단하는가"**를 정의하는 단일 참조 문서입니다. 사내 보고용 신뢰성을 위해 모든 룰을 명시적으로 정리합니다.

기준 버전:
- **JDK**: 26 (Temurin 26.0.1)
- **JDBC API**: 1.0 ~ 4.5
- **분석 대상 인터페이스 수**: 34개 (RowSet 계열 제외)
- **분석 대상 메서드 수**: 889개 (각 시그니처 단위)

---

## 0. 핵심 개념

이 도구가 답하려는 질문: **"이 JDBC 드라이버의 소스 코드는 JDBC 표준 API의 몇 %를 의미 있게 구현하고 있는가?"**

답을 내려면 세 가지를 정의해야 합니다:

| 정의 | 답 |
|---|---|
| 분모 — **어떤 메서드가 카운트 대상인가?** | § 1 |
| 어디서 찾을지 — **구현 클래스를 어떻게 식별하는가?** | § 2 |
| 어떻게 매칭하는가 — **spec 메서드와 source 메서드를 어떻게 짝짓는가?** | § 3 |
| 어떻게 판단하는가 — **각 메서드가 어떻게 분류되는가?** | § 4 |

---

## 1. 분모 정의 — 어떤 메서드를 카운트하는가

### 1.1 기준 JDK와 JDBC 버전

- **JDK 26 src.zip**에서 직접 추출 (스펙 재생성 로직은 git 히스토리에 보관)
- JavaDoc의 `@since` 태그를 JDBC 버전으로 매핑
- 매핑 표:

| Java `@since` | JDBC 버전 | 시점 |
|---|---|---|
| `1.1` | **1.0** | JDK 1.1 |
| `1.2` | **2.0** | JDK 1.2 |
| `1.4` | **3.0** | JDK 1.4 |
| `1.6`, `6` | **4.0** | Java 6 |
| `1.7`, `7` | **4.1** | Java 7 |
| `1.8`, `8` | **4.2** | Java 8 |
| `9` ~ `23` | **4.3** | Java 9~23 |
| `24`, `25` | **4.4** | Java 24~25 (메서드 신규 추가 없음) |
| `26` 이상 | **4.5** | Java 26+ |

소스: JdkVersionMapping.kt (스펙 추출 도구는 v2.0.0에서 제거 — git 히스토리에 보관)

### 1.2 포함 패키지

| 패키지 | 인터페이스 수 | 비고 |
|---|---|---|
| `java.sql.*` | 24 | public interface 중 SQLData·NClob·ShardingKey 제외 |
| `javax.sql.*` (RowSet 제외) | 8 | DataSource, PooledConnection, XA* 등 (EventListener 2종 제외) |
| `javax.transaction.xa.*` | 2 | XAResource, Xid |
| **합계** | **34** | |

소스: [JdbcSpecLoader.kt:91-130](../app/src/main/kotlin/com/jdbcchecker/spec/JdbcSpecLoader.kt:91)

### 1.3 인터페이스 전체 목록 (참고)

`java.sql` (24): Connection, Statement, PreparedStatement, CallableStatement, ResultSet, DatabaseMetaData, Driver, SQLInput, SQLOutput, Array, Struct, Ref, Blob, Clob, Savepoint, ParameterMetaData, ResultSetMetaData, SQLXML, RowId, Wrapper, DriverAction, SQLType, ConnectionBuilder, ShardingKeyBuilder

`javax.sql` (8): CommonDataSource, DataSource, ConnectionPoolDataSource, PooledConnection, XAConnection, XADataSource, PooledConnectionBuilder, XAConnectionBuilder

`javax.transaction.xa` (2): XAResource, Xid

### 1.4 RowSet 제외 결정

다음 6개 인터페이스는 의도적으로 분석에서 제외:
- `javax.sql.RowSet`, `RowSetInternal`, `RowSetListener`, `RowSetMetaData`, `RowSetReader`, `RowSetWriter`

이유:
1. **JDBC 드라이버가 구현하지 않는다** — 5개 메이저 드라이버(CUBRID, pgjdbc, MySQL, MariaDB, MSSQL) 모두 RowSet 구현체 0건 (소스 grep으로 확인)
2. **JDK 자체의 책임** — `com.sun.rowset.JdbcRowSetImpl`, `CachedRowSetImpl` 등 reference impl이 JDK에 포함됨 (`java.sql.rowset` 모듈)
3. **포함 시 모든 드라이버 커버리지가 일률적으로 ~15%p 하락** — 분모만 늘리고 분자는 +0, 의미 있는 정보 없음

### 1.5 메서드 카운팅 단위

- **각 메서드 시그니처 1개당 1**로 카운트
- **모든 오버로드 개별 카운트**:
  ```
  setObject(int, Object)           ← 1개
  setObject(int, Object, int)      ← 1개 (별개)
  setObject(int, Object, int, int) ← 1개 (별개)
  setObject(int, Object, SQLType)  ← 1개 (별개)
  ```
- `abstract` 메서드와 `default` 메서드 모두 포함
- 인터페이스의 static 메서드는 reflection 기준으로 `getDeclaredMethods()`에 포함되면 카운트

### 1.6 인터페이스 자체에 메서드가 없는 경우 (marker interface)

`NClob`, `ShardingKey` 같은 marker interface는 자체 메서드가 0개입니다 (상위 인터페이스 메서드만 가짐). 이런 경우:
- spec yaml 파일은 생성되지 않음 (메서드 0개라 의미 없음)
- 메서드 커버리지 지표에 아무 기여도 하지 않으므로, spec-1부터는 분석 대상 목록(`JDBC_INTERFACES`)에서도 제외됨

### 1.7 검증 (Layer 1)

L1_compare_reflection.py(검증 스크립트는 리포 외부 아카이브에 보관)가 JDK 26 reflection ground truth와 우리 spec yaml을 비교. 최신 검증 결과 **34/34 인터페이스, 889/889 메서드 100% 일치**.

---

## 2. Entry Class 자동 탐색 — 구현 클래스를 어떻게 찾는가

각 JDBC 인터페이스마다 "이 드라이버에서 이 인터페이스를 대표하는 구현 클래스"를 1개 선정합니다. 분류는 그 클래스에서 시작.

### 2.1 후보 풀

CompilationUnit으로 파싱된 모든 `.java` 파일에서:
- `class` 키워드 사용 (enum/interface 제외)
- 그 클래스가 **목표 JDBC 인터페이스를 직접 또는 transitive로 구현**

[JdbcInterfaceResolver.kt:43-50](../app/src/main/kotlin/com/jdbcchecker/resolver/JdbcInterfaceResolver.kt:43)

> **알려진 한계**: `enum` 클래스는 후보에서 빠짐. MySQL의 `MysqlType` enum이 `java.sql.SQLType`을 구현하지만 인식 안 됨. 영향: 4,480 분류 중 3건(0.07%). § 6 참조.

### 2.2 선정 우선순위 (7단계 필터)

후보가 여러 개일 때 다음 순서로 좁힘:

| 우선순위 | 필터 | 의도 |
|---|---|---|
| 1 | **Concrete > abstract** | 추상 클래스는 인스턴스화 안 되니 fallback으로만 |
| 2 | **Top-level > nested** | inner/anonymous 클래스보다 표준 클래스 우선 |
| 3 | **Clean name > Wrapper/Pooling/Proxy/Adapter/Delegate** | `StatementWrapper`보다 `StatementImpl` 선호 |
| 4 | **Direct implementor > transitive** | `implements Connection`이 `extends FooConn`보다 우선 |
| 5 | **Primary interface match** | `SQLServerCallableStatement` (primary=CallableStatement)는 `java.sql.Statement` entry class로 선택되지 않음 |
| 6 | **Leaf subclass > superclass** | `SQLServerConnection43`이 `SQLServerConnection`보다 우선 (JDBC 4.3+ 메서드 포함) |
| 7 | **메서드 수 많은 것** | 그래도 동률이면 가장 풍부한 구현 |

[JdbcInterfaceResolver.kt:167-225](../app/src/main/kotlin/com/jdbcchecker/resolver/JdbcInterfaceResolver.kt:167)

#### Primary interface match (우선순위 5) 세부

각 클래스의 "primary JDBC interface"는 그 클래스가 구현하는 JDBC 인터페이스 중 **가장 specific한 것** (다른 후보가 extend하지 않는 leaf).

`JDBC_INTERFACE_ANCESTORS` 테이블 ([resolver:382-431](../app/src/main/kotlin/com/jdbcchecker/resolver/JdbcInterfaceResolver.kt:382)):
- `CallableStatement → {PreparedStatement, Statement, Wrapper}`
- `PreparedStatement → {Statement, Wrapper}`
- `NClob → {Clob}`
- `DataSource → {CommonDataSource, Wrapper}`
- 등등

예: `SQLServerCallableStatement`의 primary = `CallableStatement` (CallableStatement가 PreparedStatement/Statement를 transitive로 extend). 그래서 `java.sql.Statement` entry class를 고를 때 이 클래스는 후보에서 빠짐.

### 2.3 명시적 Override

자동 탐색을 무시하고 지정하는 방법:

| 방법 | 우선순위 | 예시 |
|---|---|---|
| CLI `--entry-class iface=class` | **최우선** | `--entry-class java.sql.Connection=com.foo.MyConn` |
| Profile YAML `entryClasses` | CLI 다음 | `entryClasses: { java.sql.Connection: com.foo.MyConn }` |
| 자동 탐색 | fallback | (위 둘이 없을 때) |

### 2.4 검증 (Layer 2)

L2_source_matching.py(검증 스크립트는 리포 외부 아카이브에 보관)가 NOT_FOUND로 분류된 메서드 전부를 인터페이스 구현체 소스 트리에서 type-aware grep으로 cross-check. 최신 결과: **5드라이버 658개 NOT_FOUND 중 진짜 false negative는 3개 (0.46%)**.

---

## 3. 메서드 매칭 — Spec 메서드와 Source 메서드를 어떻게 짝짓는가

Entry class에서 시작해서 spec method와 일치하는 소스 method를 찾습니다.

### 3.1 매칭 규칙

다음 모두 일치해야 매칭 성공:

1. **메서드 이름 정확 일치** (`equals`)
2. **파라미터 개수 일치**
3. **파라미터 타입 일치** (정규화 후)

[ImplementationDetector.kt:139-148](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:139)

### 3.2 파라미터 타입 정규화

타입 비교 전 다음 정규화:

| 처리 | 예시 |
|---|---|
| 제네릭 제거 | `Class<T>` → `Class`, `Map<String,Object>` → `Map` |
| FQN ↔ Simple name 양방 매칭 | `java.lang.String` ↔ `String` |
| Varargs 변환 | `String...` → `String[]` |

[ImplementationDetector.kt:158-179](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:158)

### 3.3 상속 체인 탐색

Entry class에서 메서드를 못 찾으면 부모 클래스로 올라가며 재귀 탐색:

```
SQLServerCallableStatement
  ↑ extends
SQLServerPreparedStatement
  ↑ extends
SQLServerStatement       ← 여기서 executeQuery(String) 발견
```

[ImplementationDetector.kt:47-65](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:47)

### 3.4 같은 simple name이 여러 package에 있을 때

`Statement`라는 클래스가 `com.mysql.cj.jdbc.Statement`와 `com.mysql.cj.xdevapi.Statement`처럼 두 패키지에 있을 수 있음. 이때:
- 참조 클래스(자식 클래스)와 **같은 패키지**의 것을 우선
- 그래도 없으면 첫 번째 후보

[ImplementationDetector.kt:76-102](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:76)

### 3.5 매칭 실패 시

상속 체인을 끝까지 올라가도 메서드를 찾지 못하면 → `NOT_FOUND`

---

## 4. 7단계 분류 룰셋 — 메서드 구현 상태 판단

매칭된 메서드의 body를 분석하여 7개 분류 중 하나로 판정.

### 4.1 7단계 분류 정의

| 분류 | 의미 | 점수 | Level 1 |
|---|---|---|---|
| `FULLY_IMPLEMENTED` | 실제 로직 보유 | 4 | IMPLEMENTED |
| `PARTIAL` | 일부 경로는 throw, 일부 경로는 실제 로직 | 3 | IMPLEMENTED |
| `DELEGATES` | 다른 메서드를 호출하여 결과 반환 (위임) | 2 | IMPLEMENTED |
| `RETURNS_DEFAULT` | null/0/false/literal 같은 placeholder 반환, no-op | 1 | STUB |
| `THROWS_UNSUPPORTED` | UnsupportedOperationException 또는 SQLFeatureNotSupportedException throw | 1 | STUB |
| `THROWS_SQL_EXCEPTION` | SQLException 계열 throw (실질적으로 미지원) | 1 | STUB |
| `NOT_FOUND` | spec에 있지만 source에서 매칭 실패 | 0 | NOT_FOUND |

점수는 diff 비교(IMPROVED/REGRESSED 판정)에 사용. 소스: [ImplementationStatus.kt](../app/src/main/kotlin/com/jdbcchecker/model/ImplementationStatus.kt)

### 4.2 분류 의사결정 흐름 (Decision Tree)

```
analyzeMethodBody(method)
├─ body 없음 (abstract / interface) → NOT_FOUND
├─ body 비어 있음 `{}` → RETURNS_DEFAULT
│
├─ 단일 statement
│   ├─ throw <Exception> → classifyThrow() [§ 4.3]
│   ├─ return <default-literal> → RETURNS_DEFAULT
│   │     (null, false, 0, 0L, 0.0, "")
│   ├─ return <method-call>() → DELEGATES
│   └─ try { return <method-call>() } catch (…) → DELEGATES
│
└─ 다중 statement
    ├─ A. 마지막이 throw + 본문에 return 없음 → classifyThrow() [§ 4.3]
    │     (= throw-only body: setup/logging + 최종 throw)
    ├─ B. 마지막이 stubHelper 호출 + 본문에 return 없음 [Profile only]
    │     → profile에 정의된 classify (THROWS_UNSUPPORTED 또는 THROWS_SQL_EXCEPTION)
    ├─ C. 본문 어디든 unsupported throw 패턴 있음 → PARTIAL
    │     (즉 일부 경로는 throw, 다른 경로는 정상 — 우리는 보수적으로 PARTIAL로 봄)
    ├─ D. statements ≤ 3 AND
    │     "validation/logging only" 또는
    │     "validation/logging + 마지막 return literal"
    │     → RETURNS_DEFAULT [Bug 8 + Bug 9]
    │     예:
    │       public void setSchema(String s) { checkOpen(); }           ← void no-op
    │       public boolean isReadOnly() { checkOpen(); return false; } ← literal false
    │       public boolean supportsX() { checkOpen(); return true; }   ← literal true (Bug 9)
    │       public int getMaxX() { checkOpen(); return 100; }          ← literal int
    └─ E. 그 외 → FULLY_IMPLEMENTED
```

[ImplementationDetector.kt:204-307](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:204)

### 4.3 throw 분류 (classifyThrow)

`throw <expr>`의 expression 문자열을 분석:

| 패턴 | 분류 |
|---|---|
| `UnsupportedOperationException` 포함 | `THROWS_UNSUPPORTED` |
| `SQLFeatureNotSupportedException` 포함 | `THROWS_UNSUPPORTED` |
| `*SQL*Exception` 패턴 (정규식: `\b\w*SQL\w*Exception\b`) — SQLException, PSQLException, SQLServerException, SQLClientInfoException 등 | `THROWS_SQL_EXCEPTION` |
| `NotUpdatable`, `OperationNotSupportedException`, `NotImplementedException`, `NotSupportedException` (정확 단어) | `THROWS_SQL_EXCEPTION` |
| Profile `stubExceptionClasses`에 명시된 이름 | `THROWS_SQL_EXCEPTION` |
| 그 외 | `THROWS_UNSUPPORTED` (fallback) |

[ImplementationDetector.kt:322-345](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:322)

### 4.4 "Validation/Logging call" 정의

다음 메서드 이름으로 시작하면 validation/logging으로 분류:
- `check*`, `assert*`, `verify*`
- `log*`, `trace*`, `debug*`, `warn*`
- 정확히 일치: `info`, `fine`, `finer`, `finest`, `entering`, `exiting`, `severe`

[ImplementationDetector.kt:537-550](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:537)

### 4.5 "Literal expression" 정의 (Bug 9)

`return <expr>`에서 `<expr>`이 다음 중 하나면 literal로 인정 (RETURNS_DEFAULT 판정에 사용):
- `null` literal
- `true` 또는 `false`
- 모든 integer literal (0 뿐만 아니라 100, 1073741823 등)
- 모든 long literal
- 모든 double literal
- 모든 string literal (빈 문자열뿐만 아니라 `"CUBRID"` 등)
- 모든 char literal

[ImplementationDetector.kt:560-578](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:560)

### 4.6 Throw-only body 판정 (Bug 4)

"마지막 statement가 throw이고 본문에 return statement가 없는" body는 모든 경로가 throw로 종료된다고 판정 (PARTIAL이 아닌 stub). 예:

```java
public final ResultSet executeQuery(String sql) {
    if (logger.isLoggable(FINER)) logger.entering(...);  // setup
    MessageFormat form = new MessageFormat(...);          // setup
    Object[] msgArgs = {"executeQuery()"};                // setup
    throw new SQLServerException(this, form.format(msgArgs), null, 0, false);  // ← terminal
}
// → classifyThrow → THROWS_SQL_EXCEPTION (SQLServerException 매칭)
```

본문에 `return`이 있으면 (어딘가에 정상 경로 존재) → PARTIAL로 fallback.

[ImplementationDetector.kt:354-395](../app/src/main/kotlin/com/jdbcchecker/detector/ImplementationDetector.kt:354)

### 4.7 검증 (Layer 3)

L3_independent_classifier.py(검증 스크립트는 리포 외부 아카이브에 보관)가 우리 detector와 별개 알고리즘으로 분류 → cross-check. 최신 결과: **4,480쌍 중 87.1% 동의, 진짜 분석기 오류 추정 ~40건 = 정확도 ~99%**.

---

## 5. Driver Profile — 드라이버별 튜닝

5개 메이저 드라이버에 대해 사전 정의된 profile을 자동 적용하여 정확도 향상.

### 5.1 Profile 자동 감지

소스 파일들의 package 선언을 보고 매칭되는 번들 profile을 자동 적용. 매핑:

| Package prefix | Profile |
|---|---|
| `com.microsoft.sqlserver.jdbc` | mssql |
| `com.mysql.cj` | mysql |
| `org.postgresql` | pgjdbc |
| `org.mariadb.jdbc` | mariadb |
| `cubrid.jdbc` | cubrid |

[ProfileResolver.kt](../app/src/main/kotlin/com/jdbcchecker/profile/ProfileResolver.kt), 번들 YAML: [resources/profiles/](../app/src/main/resources/profiles/)

### 5.2 Profile의 영향

Profile은 두 가지로 분석 결과에 영향:

#### (a) `entryClasses` — Entry class 명시
자동 탐색을 override해서 특정 인터페이스의 entry class를 고정. 예 (mssql):
```yaml
entryClasses:
  java.sql.Connection: com.microsoft.sqlserver.jdbc.SQLServerConnection43
  java.sql.Statement:  com.microsoft.sqlserver.jdbc.SQLServerStatement
```

#### (b) `stubHelpers` — Throw helper 인식
driver-specific 헬퍼 메서드가 본질적으로 throw임을 알려줌. 예 (mssql):
```yaml
stubHelpers:
  - callPattern: "SQLServerException.throwNotSupportedException"
    classify: THROWS_SQL_EXCEPTION
```

이 helper 호출이 multi-statement body의 마지막 statement이고 본문에 return이 없으면 throw처럼 취급 → classify에 명시된 stub 분류 적용.

#### (c) `stubExceptionClasses` — 추가 stub exception
`classifyThrow()` 분류에서 인식할 driver-specific exception 클래스명 추가.

### 5.3 우선순위

```
--profile <name>        (번들 profile 이름 명시, 최우선)
    ↓
auto-detect             (package prefix 매칭)
    ↓
none                    (generic 분석만)
```

### 5.4 5개 번들 Profile 요약

| Profile | entry class 명시 | stubHelper | stubException |
|---|---|---|---|
| **mssql** | Connection→SQLServerConnection43, Statement/PreparedStatement/CallableStatement 각각 | `SQLServerException.throwNotSupportedException` | — |
| **mysql** | 13개 인터페이스 명시 | — | `NotUpdatable`, `OperationNotSupportedException` |
| **pgjdbc** | 13개 인터페이스 명시 | — | — |
| **mariadb** | 11개 인터페이스 명시 | — | — |
| **cubrid** | 10개 인터페이스 명시 | — | — |

---

## 6. 알려진 한계 (Known Limitations)

검증 과정에서 확인된 분석기의 한계와 영향 범위.

### 6.1 Enum 후보 미인식 (Bug 11)

**증상**: `enum X implements <JDBC interface>` 같은 enum 구현체가 entry-class 후보에서 빠짐.

**예시**: MySQL의 `com.mysql.cj.MysqlType` enum이 `java.sql.SQLType`을 구현하지만 인식 안 됨.

**영향**: 5드라이버 × 896 메서드 = 4,480 분류 중 **3건** (MySQL의 SQLType getName/getVendor/getVendorTypeNumber). 0.07%. (2026-05-21 검증 당시 스펙 896 메서드 기준; 현행 spec-1은 889)

**우회**: 해당 메서드를 NOT_FOUND로 보고. 실제 보고 시 별도 주석.

### 6.2 Helper 호출 + 리터럴 return 패턴 (Bug 10)

**증상**: 다음 패턴이 PARTIAL로 분류되지만 사실상 stub:
```java
public Array getArray(int i) throws SQLException {
    SQLServerException.throwNotSupportedException(stmt.connection, stmt);
    return null;  // unreachable
}
```

마지막 statement가 `return null`이라 throw-only body 검사 fail. stubHelper 검사도 마지막이 throw가 아니라 통과 못함. 결과적으로 PARTIAL.

**영향**: MSSQL 약 29건, MySQL 약 10건. 총 ~40건 / 4,480 = 0.9%.

**우회**: 해당 패턴은 실제로 stub이지만 PARTIAL로 보수 분류. 보고서에서 명시.

### 6.3 RowSet 계열 제외

§ 1.4 참조. 의도된 제외이며 알려진 한계로 명시.

### 6.4 SQLInput / SQLOutput SPI

`SQLInput`/`SQLOutput`은 user-defined type 처리용 SPI 인터페이스. 대부분 드라이버가 구현하지 않음 (5드라이버 모두 부분 또는 0). 우리 spec에 포함되어 있고, NOT_FOUND가 정상.

### 6.5 동시성/Thread 안전성

이 도구는 **정적 분석**만 수행. 메서드의 thread safety, 트랜잭션 동작, 실제 DB 연결 검증은 범위 밖.

### 6.6 영향 정량 종합

| 한계 | 영향 (4,480 분류 중) | 비중 |
|---|---|---|
| Enum 미인식 | 3 | 0.07% |
| Helper + literal return 패턴 | ~40 | 0.9% |
| 기타 분류 모호 케이스 | ~20 | 0.45% |
| **합계 (보수적)** | **~63** | **~1.4%** |

→ 분석기 정확도 약 **98.6% 이상**.

---

## 7. 결과의 의미 — 무엇을 보고 무엇을 보지 않는가

### 보는 것 ✓
- spec method가 source에 **존재하는지** (NOT_FOUND 여부)
- 존재한다면 **의미 있는 로직이 있는지** (stub vs implementation)
- 7단계로 **세부 분류** (FULLY/DELEGATES/PARTIAL/UNSUPPORTED/SQL_EXCEPTION/DEFAULT)

### 보지 않는 것 ✗
- **메서드가 정확히 동작하는지** (정확성, 버그 유무)
- **성능, 메모리, thread safety**
- **트랜잭션 의미론, isolation level 정합성**
- **SQL spec 호환성** (driver가 보낸 SQL이 정확한가)
- **에러 메시지 품질**

이 도구의 측정값은 "JDBC API 구현 완성도의 정량 지표" 한 가지로 해석해야 합니다. 절대적 품질 척도가 아닙니다.

---

## 8. Decision Flow 한 페이지 요약

```
┌─────────────────────────────────────────────────────────────┐
│ Input: JDBC driver source (path or Git URL)                 │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. SPEC LOAD                                                │
│   - JDK 26 기반 34개 인터페이스, 889개 메서드               │
│   - RowSet 제외                                             │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. PROFILE RESOLVE                                          │
│   --profile <name> > auto-detect                            │
│   → entry class 매핑 + stub helper 목록 확정                │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. ENTRY CLASS 자동 탐색 (인터페이스별)                     │
│   concrete > top-level > clean > direct > primary > leaf    │
│   > most-methods 순으로 1개 선정                            │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. 메서드 매칭 (spec method × 단계 3 entry class)           │
│   이름 정확 + 파라미터 type 정규화 매칭                     │
│   extends chain walking                                     │
│   매칭 실패 → NOT_FOUND                                     │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. BODY 분류 (7단계)                                        │
│   body 분석으로 FULLY/DELEGATES/PARTIAL/UNSUP/SQL_EXC/      │
│   DEFAULT 중 하나로 판정 (§ 4.2 결정 트리)                  │
└─────────────────────┬───────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ Output: AnalysisReport                                      │
│   - 인터페이스별 + 버전별 + 전체 coverage                   │
│   - 메서드별 7단계 분류                                     │
│   - console / json / html / (docx — 별도 스크립트)          │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. 신뢰성 근거 (검증 결과 요약)

| 검증 단계 | 측정 | 결과 |
|---|---|---|
| Layer 1 — Spec 완전성 | JDK 26 reflection ground truth vs 우리 spec | **100% 일치** (34/34, 889/889) |
| Layer 2 — Source Matching | 5드라이버 658 NOT_FOUND를 grep으로 cross-check | False NOT_FOUND **3 / 658 (0.46%)** |
| Layer 3 — Classification | 독립 분류기와 4,480쌍 비교 | 동의율 **87.1%**, 진짜 오류 ~40건 |
| **종합 정확도 (보수적)** | | **~98.6%** |

검증 스크립트 (검증 스크립트는 리포 외부 아카이브에 보관):
- verification/scripts/JdbcSpecReflection.java — JDK 26 ground truth 생성
- verification/scripts/L1_compare_reflection.py
- verification/scripts/L2_source_matching.py
- verification/scripts/L3_independent_classifier.py
- verification/scripts/L3_compare.py

상세 결과: verification/REPORT.html / REPORT.pdf (리포 외부 아카이브에 보관)

---

## 10. 변경 이력 (분석 룰의 진화 — 누적 발견 버그)

| Bug# | 영역 | 수정 내용 |
|---|---|---|
| 1 | classifyThrow | `*SQLException` suffix regex로 PSQLException/SQLServerException 등 인식 |
| 2 | Resolver | inner class도 후보에 포함 (top-level 우선) — MariaDB XAResource 복구 |
| 3 | Resolver | leaf subclass 우선 — SQLServerConnection43 복구 |
| 4 | analyzeMethodBody | throw-only multi-statement body는 PARTIAL 아닌 stub로 분류 |
| 5 | Resolver | clean-name이 direct-implementor보다 우선 — MySQL StatementWrapper 회피 |
| 6 | Resolver | primary interface match — SQLServerCallableStatement가 Statement로 오선택 방지 |
| 7 | JSON 직렬화 | `THROWS_SQLEXCEPTION` → `THROWS_SQL_EXCEPTION` 키 일관성 |
| 8 | analyzeMethodBody | `validation_call(); return literal-default;` → RETURNS_DEFAULT |
| 9 | isLiteralExpression | `return true`/`return 100`/`return "CUBRID"` 등 모든 리터럴을 default로 인정 |
| 10 | (알려진 한계) | helper-call + literal return 패턴은 여전히 PARTIAL (보수적) |
| 11 | (알려진 한계) | enum 후보 미인식 (MysqlType) |

---

*이 문서는 단일 참조 출처입니다. 분석기 동작에 의문이 생기면 이 문서의 룰과 검증 결과를 우선 확인하세요. 코드와 이 문서 사이에 불일치가 발견되면 [`docs/ANALYSIS_RULES.md`](./ANALYSIS_RULES.md)를 갱신해주세요.*
