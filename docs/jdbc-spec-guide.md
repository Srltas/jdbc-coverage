# JDBC 스펙 관리 가이드

이 문서는 JDBC Compliance Checker가 사용하는 JDBC 스펙 데이터의 구조, 번들 스펙과 커스텀 스펙의 차이, JDK 소스에서 스펙을 재추출하는 방법을 설명합니다.

## 번들 스펙

프로젝트에 JDK 21 기준 JDBC 스펙 YAML이 이미 포함되어 있습니다. `analyze` 커맨드 실행 시 **별도 설정 없이** 자동으로 사용됩니다.

```
app/src/main/resources/jdbc-spec/
├── _summary.yaml                      ← 전체 요약 정보
├── java.sql.Connection.yaml           ← 인터페이스별 메서드 목록
├── java.sql.Statement.yaml
├── java.sql.ResultSet.yaml
├── java.sql.DatabaseMetaData.yaml
├── javax.sql.DataSource.yaml
├── ...                                ← 총 42개 파일
```

> **참고**: 번들 스펙에는 41개 인터페이스 1,017개 메서드가 포함되어 있지만, 분석에는 **19개 핵심 인터페이스 771개 메서드**만 사용됩니다. `ConnectionBuilder`, `DriverAction`, `RowSet` 등 드라이버가 직접 구현하지 않는 인터페이스는 분석에서 제외됩니다.

**대부분의 사용자는 이 단계를 건드릴 필요가 없습니다.**

---

## YAML 파일 구조

### 인터페이스별 YAML

각 YAML 파일은 하나의 JDBC 인터페이스에 대한 메서드 목록입니다:

```yaml
# java.sql.Connection.yaml
interfaceName: java.sql.Connection
since: 1.0
methodCount: 58
methods:
- name: clearWarnings
  params: []
  returns: void
  since: 1.0
- name: createStatement
  params: []
  returns: Statement
  since: 1.0
- name: createStatement
  params: [int, int]
  returns: Statement
  since: 2.0
- name: setSchema
  params: [String]
  returns: void
  since: 4.1
- name: beginRequest
  params: []
  returns: void
  since: 4.3
```

| 필드 | 설명 |
|------|------|
| `interfaceName` | 정규화된 인터페이스명 (예: `java.sql.Connection`) |
| `since` | 이 인터페이스가 처음 도입된 JDBC 버전 |
| `methodCount` | 이 인터페이스의 메서드 수 |
| `methods[].name` | 메서드 이름 |
| `methods[].params` | 파라미터 타입 목록 (간략한 이름 사용) |
| `methods[].returns` | 반환 타입 |
| `methods[].since` | 이 메서드가 도입된 JDBC 버전 |

`since` 필드는 JDK 소스의 `@since` Javadoc 태그에서 추출됩니다.

### 요약 YAML

`_summary.yaml`은 전체 추출 결과의 요약입니다:

```yaml
totalInterfaces: 41
totalMethods: 1017
interfaces:
- name: java.sql.Connection
  methodCount: 58
  since: 1.0
- name: java.sql.Statement
  methodCount: 54
  since: 1.0
# ...
```

---

## JDK 소스에서 스펙 재추출

새로운 JDK 버전이 출시되어 JDBC 스펙이 변경되었을 때만 이 과정이 필요합니다.

### Step 1: JDK 소스 준비

OpenJDK 소스에서 `java/sql/`과 `javax/sql/` 디렉터리가 필요합니다.

#### 방법 1: OpenJDK 소스에서 추출

```bash
# OpenJDK 소스 클론 (shallow)
git clone --depth 1 https://github.com/openjdk/jdk.git /tmp/openjdk

# JDBC 관련 소스 위치:
#   /tmp/openjdk/src/java.sql/share/classes/java/sql/
#   /tmp/openjdk/src/java.sql/share/classes/javax/sql/

# 작업 디렉터리에 복사
mkdir -p ./my-jdk-sources/java/sql ./my-jdk-sources/javax/sql
cp /tmp/openjdk/src/java.sql/share/classes/java/sql/*.java ./my-jdk-sources/java/sql/
cp /tmp/openjdk/src/java.sql/share/classes/javax/sql/*.java ./my-jdk-sources/javax/sql/
```

#### 방법 2: 프로젝트 포함 소스 사용

이 프로젝트의 `jdk-sources/` 디렉터리에 JDK 21 소스가 이미 준비되어 있습니다:

```
jdk-sources/
├── java/
│   └── sql/
│       ├── Connection.java
│       ├── Statement.java
│       ├── ResultSet.java
│       └── ... (30개 파일)
└── javax/
    └── sql/
        ├── DataSource.java
        ├── CommonDataSource.java
        └── ... (19개 파일)
```

### JDK 소스 디렉터리 요구 구조

`extract-spec` 커맨드에 전달하는 디렉터리는 반드시 다음 구조를 가져야 합니다:

```
<jdk-source-root>/
├── java/
│   └── sql/
│       ├── Connection.java
│       ├── Statement.java
│       └── ...
└── javax/
    └── sql/
        ├── DataSource.java
        └── ...
```

### Step 2: 스펙 추출 실행

```bash
# 전체 인터페이스 추출
jdbc-checker extract-spec ./jdk-sources -o ./my-custom-spec

# 특정 인터페이스만 추출
jdbc-checker extract-spec ./jdk-sources \
  -o ./my-custom-spec \
  --interfaces Connection,Statement,ResultSet
```

추출 결과:

```
my-custom-spec/
├── _summary.yaml
├── java.sql.Connection.yaml
├── java.sql.Statement.yaml
├── java.sql.ResultSet.yaml
└── ...
```

### Step 3: 추출된 스펙 사용

추출된 스펙을 사용하는 방법은 두 가지입니다:

#### 런타임에 외부 스펙 지정

빌드 없이 바로 사용할 수 있습니다:

```bash
jdbc-checker analyze ./src/jdbc --spec-dir ./my-custom-spec
```

#### 번들 스펙 교체

프로젝트에 포함된 번들 스펙을 교체하고 다시 빌드합니다:

```bash
# 기존 번들 스펙 백업 (선택)
cp -r app/src/main/resources/jdbc-spec/ app/src/main/resources/jdbc-spec-backup/

# 새 스펙으로 교체
cp ./my-custom-spec/*.yaml app/src/main/resources/jdbc-spec/

# 다시 빌드
./gradlew :app:installDist
```

---

## JDK 버전과 JDBC 버전 매핑

`@since` 태그의 JDK 버전은 다음과 같이 JDBC 버전으로 매핑됩니다:

| `@since` 값 | JDBC 버전 |
|-------------|-----------|
| `1.1` | JDBC 1.0 |
| `1.2` | JDBC 2.0 |
| `1.4` | JDBC 3.0 |
| `1.6` | JDBC 4.0 |
| `1.7` | JDBC 4.1 |
| `1.8` | JDBC 4.2 |
| `9` 이상 | JDBC 4.3 |

> **JDK 21 하나로 충분한 이유**: JDK 21의 `@since` 태그에는 JDBC 1.0부터 4.3까지의 이력이 모두 포함되어 있습니다. JDK 8 이후 JDBC 인터페이스에서 제거된 메서드는 없으므로, JDK 21 소스만으로 전체 JDBC 스펙 이력을 추출할 수 있습니다.
