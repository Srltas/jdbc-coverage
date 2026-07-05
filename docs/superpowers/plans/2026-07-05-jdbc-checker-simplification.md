# JDBC Compliance Checker 경량화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검증된 분석 엔진은 유지한 채 CLI를 `analyze`+`dashboard` 2개 커맨드로 축소하고, JDBC 버전별 누적 커버리지·스냅샷 프로버넌스·히스토리 기록·트렌드 대시보드·GitHub Actions 데일리 자동화를 추가한다.

**Architecture:** Kotlin/Gradle 단일 모듈(:app), picocli CLI. 파이프라인: SourceParser(JavaParser+SymbolSolver) → JdbcInterfaceResolver → ImplementationDetector → AnalysisReport → Console/JSON 리포터. 신규: `--history <dir>`가 델타 계산 후 `history/<slug>.jsonl`(영구)과 `latest/<slug>.json`(덮어쓰기)을 기록하고, `dashboard` 커맨드가 이를 자기완결 HTML로 렌더링한다. GitHub Actions가 매일 5개 드라이버를 분석해 gh-pages에 게시한다.

**Tech Stack:** Kotlin 2.2, Gradle(:app:installDist), picocli, JavaParser Symbol Solver, Jackson(JSON/YAML), JUnit5+AssertJ. 신규 의존성 없음(kotlinx-html·kapt·picocli-codegen은 제거).

**Spec:** `docs/superpowers/specs/2026-07-05-jdbc-checker-simplification-design.md`

## Global Constraints

- **절대 `git push` 금지.** 커밋만 한다. 푸시는 사용자가 직접 한다 (gh-pages 부트스트랩 포함).
- 커밋 메시지: Conventional Commit 단일 제목 줄 + 트레일러 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- JDK 21 툴체인 (build.gradle.kts의 `JavaLanguageVersion.of(21)` 유지).
- 신규 외부 의존성 추가 금지. 대시보드 HTML은 외부 요청(CDN/폰트/이미지) 0.
- **분류 규칙 동결**: detector/resolver의 판정 로직을 변경하지 않는다. 기존 디텍터 테스트 31개는 수정 없이 계속 통과해야 한다.
- 테스트 실행 명령: `./gradlew :app:test --console=plain` (전체), `--tests '<ClassName>'` (단일 클래스).
- 모든 경로는 리포 루트 `/Users/cubrid/Devel/JDBC/java-compliance-checker` 기준 상대 경로.
- 스모크 분석 대상(Task 1~9): `verification/sources/cubrid-jdbc/src/jdbc` (Task 10에서 `../jdbc-checker-archive/`로 이동하므로 Task 11은 아카이브 경로 사용).
- 실행 격리: 시작 전 superpowers:using-git-worktrees로 워크트리를 만들되, **Task 1은 현재 dirty 워킹트리 자체를 커밋하는 작업이므로 반드시 원본 워킹트리에서 수행한 후** 워크트리를 생성한다.

---

### Task 1: 검증 기준선 커밋 (미커밋 작업 확정)

현재 워킹트리는 2026-05-21 검증 캠페인(프로파일 서브시스템, 896-메서드 스펙, 분류 룰 수정, RowSet 제거)을 담고 있지만 미커밋 상태다. HEAD는 검증 수치를 재현하지 못하므로, 데이터 디렉터리를 제외한 전부를 기준선으로 커밋한다.

**Files:**
- Commit: 모든 tracked 수정/삭제 + untracked 중 `\.java-version`, `app/src/main/kotlin/com/jdbcchecker/profile/`, `app/src/main/resources/profiles/`, `app/src/test/kotlin/com/jdbcchecker/detector/ImplementationDetectorProfileTest.kt`, `app/src/test/kotlin/com/jdbcchecker/profile/`, `docs/ANALYSIS_RULES.md`
- 커밋 제외 (Task 10에서 아카이브): `0325_jdbc_api/`, `jdbc_api/`, `jdbc-api/`, `mysql_result/`, `verification/`, `app/report.json`

**Interfaces:**
- Produces: 검증 수치를 재현하는 HEAD. 이후 모든 태스크의 출발점.

- [ ] **Step 1: 테스트가 통과하는 상태인지 확인**

Run: `./gradlew :app:test --console=plain`
Expected: `BUILD SUCCESSFUL` (현재 워킹트리 기준으로 전체 테스트 통과 — 2026-07-05 분석 시점에 확인됨)

- [ ] **Step 2: 대상 파일만 스테이징**

```bash
git add -u
git add .java-version \
  app/src/main/kotlin/com/jdbcchecker/profile/ \
  app/src/main/resources/profiles/ \
  app/src/test/kotlin/com/jdbcchecker/detector/ImplementationDetectorProfileTest.kt \
  app/src/test/kotlin/com/jdbcchecker/profile/ \
  docs/ANALYSIS_RULES.md
git status --short
```

Expected: 스테이지에 위 파일들 + tracked 수정/삭제(`M`/`D`)만 있고, `?? 0325_jdbc_api/`, `?? jdbc_api/`, `?? jdbc-api/`, `?? mysql_result/`, `?? verification/`, `?? app/report.json`은 unstaged `??`로 남아 있음.

- [ ] **Step 3: 커밋**

```bash
git commit -m "feat: driver profiles and validated 896-method spec baseline

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 4: 스모크 — 검증 수치 재현 확인**

Run:
```bash
./gradlew :app:installDist
app/build/install/jdbc-checker/bin/jdbc-checker analyze \
  verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" | grep "Overall Coverage"
```
Expected: `Overall Coverage: 40.6%` (results_v5와 동일. 다르면 STOP — 기준선이 잘못된 것이므로 원인 파악 전 진행 금지)

---

### Task 2: 스펙 스코프 정리 + SPEC_VERSION 동결 (spec-1)

드라이버가 구현하지 않는 3개 인터페이스(SQLData 3, ConnectionEventListener 2, StatementEventListener 2 = 7 메서드)를 제외하고 스펙을 `spec-1`로 동결한다. 이후 스펙 YAML 변경 금지.

**Files:**
- Modify: `app/src/main/kotlin/com/jdbcchecker/spec/JdbcSpecLoader.kt`
- Delete: `app/src/main/resources/jdbc-spec/java.sql.SQLData.yaml`, `app/src/main/resources/jdbc-spec/javax.sql.ConnectionEventListener.yaml`, `app/src/main/resources/jdbc-spec/javax.sql.StatementEventListener.yaml`, `app/src/main/resources/jdbc-spec/_summary.yaml` (수치가 낡아 유해)
- Test: `app/src/test/kotlin/com/jdbcchecker/spec/JdbcSpecLoaderTest.kt`

**Interfaces:**
- Produces: `JdbcSpecLoader.SPEC_VERSION: String` (= `"spec-1"`, companion const) — Task 7 프로버넌스가 사용. `loadAll()`은 36개 인터페이스 889개 메서드 반환.

- [ ] **Step 1: 실패하는 테스트 작성** — `JdbcSpecLoaderTest.kt`에 추가:

```kotlin
    @Test
    fun `spec scope excludes non-driver interfaces`() {
        val interfaces = loader.loadAll().map { it.interfaceName }.distinct()
        assertThat(interfaces).doesNotContain(
            "java.sql.SQLData",
            "javax.sql.ConnectionEventListener",
            "javax.sql.StatementEventListener",
        )
        assertThat(interfaces).hasSize(36)
    }

    @Test
    fun `frozen spec-1 has exactly 889 methods`() {
        assertThat(loader.loadAll()).hasSize(889)
    }

    @Test
    fun `spec version constant is spec-1`() {
        assertThat(JdbcSpecLoader.SPEC_VERSION).isEqualTo("spec-1")
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.jdbcchecker.spec.JdbcSpecLoaderTest' --console=plain`
Expected: FAIL — `hasSize(36)` (실제 39), `hasSize(889)` (실제 896), `SPEC_VERSION` 미정의(컴파일 에러)

- [ ] **Step 3: 구현** — `JdbcSpecLoader.kt` companion 수정:

`companion object {` 바로 아래에 추가:

```kotlin
        /**
         * Frozen spec identifier stamped into every snapshot. Bump ONLY when the
         * bundled YAML set or its scope changes — a bump rebases the daily history.
         */
        const val SPEC_VERSION = "spec-1"
```

`JDBC_INTERFACES` 리스트에서 다음 3줄 삭제:

```kotlin
            "java.sql.SQLData",               // JDBC 2.0
```
```kotlin
            "javax.sql.ConnectionEventListener",  // JDBC 2.0
```
```kotlin
            "javax.sql.StatementEventListener",   // JDBC 4.0
```

KDoc의 `39 interfaces total.` 문장을 다음으로 교체:

```
         * 36 interfaces total. Like RowSet, java.sql.SQLData (implemented by
         * application code) and javax.sql.Connection/StatementEventListener
         * (implemented by pool managers) are excluded — drivers do not
         * implement them.
```

리소스 삭제:

```bash
git rm app/src/main/resources/jdbc-spec/java.sql.SQLData.yaml \
       app/src/main/resources/jdbc-spec/javax.sql.ConnectionEventListener.yaml \
       app/src/main/resources/jdbc-spec/javax.sql.StatementEventListener.yaml \
       app/src/main/resources/jdbc-spec/_summary.yaml
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :app:test --console=plain`
Expected: BUILD SUCCESSFUL. (만약 889 단정이 다른 실제값으로 실패하면 STOP: `grep -c '  - name:' app/src/main/resources/jdbc-spec/<file>.yaml`로 삭제한 3개 파일의 메서드 수 합이 7인지 재확인)

- [ ] **Step 5: 새 기준 수치 기록 (이후 태스크의 회귀 기준)**

Run:
```bash
./gradlew :app:installDist -q
app/build/install/jdbc-checker/bin/jdbc-checker analyze \
  verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" | grep -E "Overall Coverage|Total:"
```
Expected: `Total: 889 methods`. Overall %는 40.6에서 소폭 이동(−7 분모 효과) — **출력된 값을 커밋 메시지 본문이 아닌 `docs/superpowers/plans/baseline-numbers.txt`에 기록**:

```bash
app/build/install/jdbc-checker/bin/jdbc-checker analyze \
  verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" \
  | grep -E "Overall Coverage|Total:" > docs/superpowers/plans/baseline-numbers.txt
git add docs/superpowers/plans/baseline-numbers.txt
```

- [ ] **Step 6: 커밋**

```bash
git add -A app/src/main/resources/jdbc-spec app/src/main/kotlin/com/jdbcchecker/spec app/src/test/kotlin/com/jdbcchecker/spec
git commit -m "feat(spec): freeze spec-1 scope at 889 methods

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: CLI 재작성 — analyze 단일 커맨드, 프로파일 API 축소

Main.kt를 전면 재작성한다: `diff`/`compare`/`extract-spec` 커맨드, Git URL/`--branch`/`--source-subdir`/`--spec-dir`/`--profile-file`/`--no-profile`/`--entry-class` 힌트 형식 제거. `<source-dir>`를 1개 이상의 패키지 루트로 직접 받는다. ProfileResolver는 2-파라미터로 축소.

**Files:**
- Modify: `app/src/main/kotlin/com/jdbcchecker/cli/Main.kt` (전면 재작성), `app/src/main/kotlin/com/jdbcchecker/profile/ProfileResolver.kt`, `app/src/main/kotlin/com/jdbcchecker/profile/DriverProfileLoader.kt` (loadFromFile 삭제)
- Delete: `app/src/main/kotlin/com/jdbcchecker/cli/ExtractSpecCommand.kt`, `app/src/main/kotlin/com/jdbcchecker/cli/SourceResolver.kt`, `app/src/main/kotlin/com/jdbcchecker/git/GitCloneService.kt` (git 패키지 통째), `app/src/main/kotlin/com/jdbcchecker/spec/extractor/` (3파일), `app/src/test/kotlin/com/jdbcchecker/spec/extractor/` (테스트)
- Test: `app/src/test/kotlin/com/jdbcchecker/profile/ProfileResolverTest.kt`, `app/src/test/kotlin/com/jdbcchecker/profile/DriverProfileLoaderTest.kt` 수정

**Interfaces:**
- Consumes: `JdbcSpecLoader.loadAll()` (Task 2), 기존 detector/resolver/reporter API.
- Produces (이후 태스크가 편집할 앵커):
  - `const val TOOL_VERSION = "2.0.0"` (Main.kt 최상단)
  - `internal fun runAnalysis(sourcePaths: List<Path>, driverName: String?, entryClasses: List<String> = emptyList(), profileName: String? = null): AnalysisReport?`
  - `internal fun dispatchOutputs(outputs: List<String>, report: AnalysisReport)`
  - `internal fun detectDriverName(source: Path): String`
  - `class AnalyzeCommand : Callable<Int>` (옵션: sources, -o, -n, --entry-class, --profile)
  - `ProfileResolver.resolve(compilationUnits: List<CompilationUnit>, profileName: String? = null): DriverProfile?`

- [ ] **Step 1: ProfileResolver 축소** — `ProfileResolver.kt`의 `resolve` 함수와 KDoc을 다음으로 교체 (`autoDetect`는 그대로 유지):

```kotlin
    /**
     * Resolve which profile to use. Returns null when no bundled profile
     * matches the source.
     *
     * Precedence: explicit `profileName` > auto-detect by package prefix.
     *
     * @throws IllegalArgumentException when `profileName` is given but no
     *     bundled profile with that name exists.
     */
    fun resolve(
        compilationUnits: List<CompilationUnit>,
        profileName: String? = null,
    ): DriverProfile? {
        if (profileName != null) {
            return loader.loadBundled(profileName)
                ?: throw IllegalArgumentException(
                    "Unknown driver profile: '$profileName'. " +
                        "Available bundled profiles: ${loader.listBundled().joinToString { it.name }}",
                )
        }
        return autoDetect(compilationUnits)
    }
```

클래스 상단 KDoc의 precedence 목록(1~5)도 위 두 단계 설명으로 갱신. `import java.nio.file.Path` 제거.

- [ ] **Step 2: DriverProfileLoader에서 loadFromFile 삭제**

`DriverProfileLoader.kt`에서 `fun loadFromFile(path: Path): DriverProfile { ... }` 함수 전체와 이제 안 쓰는 `import java.nio.file.Path`류 임포트 삭제. (`loadBundled`/`listBundled`는 유지)

- [ ] **Step 3: 프로파일 테스트 갱신**

`ProfileResolverTest.kt`: `profileFile takes top precedence` 테스트와 `disableProfile returns null even when source would match` 테스트 **삭제**. 나머지 테스트의 `resolver.resolve(...)` 호출은 이미 2-파라미터 형태와 호환(이름 있는 인자 `compilationUnits`, `profileName`만 사용)이므로 그대로 둔다. `import java.nio.file.Files` 삭제.

`DriverProfileLoaderTest.kt`: `loadFromFile reads YAML from disk`, `loadFromFile throws when file does not exist`, `loadFromFile throws on missing required name field` 테스트 3개 **삭제**. `assertThatThrownBy` 임포트와 `java.nio.file.Files` 임포트가 미사용이 되면 함께 삭제.

- [ ] **Step 4: Main.kt 전면 재작성** — 파일 전체를 다음 내용으로 교체:

```kotlin
package com.jdbcchecker.cli

import com.jdbcchecker.detector.ImplementationDetector
import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.MethodResult
import com.jdbcchecker.parser.SourceParser
import com.jdbcchecker.profile.DriverProfile
import com.jdbcchecker.profile.ProfileResolver
import com.jdbcchecker.report.console.ConsoleReporter
import com.jdbcchecker.report.json.JsonReporter
import com.jdbcchecker.resolver.JdbcInterfaceResolver
import com.jdbcchecker.spec.JdbcSpecLoader
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable

/** Single source of truth for the tool version (shown in --version and stamped into reports). */
const val TOOL_VERSION = "2.0.0"

@Command(
    name = "jdbc-checker",
    mixinStandardHelpOptions = true,
    version = ["jdbc-compliance-checker $TOOL_VERSION"],
    description = ["Measure how much of the JDBC API a driver's source code implements."],
    subcommands = [
        AnalyzeCommand::class,
    ],
)
class JdbcCheckerCommand : Runnable {
    override fun run() {
        CommandLine.usage(this, System.out)
    }
}

// ---------------------------------------------------------------------------
// Analysis pipeline
// ---------------------------------------------------------------------------

/**
 * Runs the full analysis pipeline over one or more package-root source directories.
 *
 * @param sourcePaths package roots (e.g. driver/src/main/java); each must be a directory
 * @param driverName report display name (auto-detected from the first path if null)
 * @param entryClasses explicit "iface=class" pins from the CLI (win over profile pins)
 * @param profileName bundled profile name; null = auto-detect by package prefix
 * @return [AnalysisReport] or null if the pipeline fails
 */
internal fun runAnalysis(
    sourcePaths: List<Path>,
    driverName: String?,
    entryClasses: List<String> = emptyList(),
    profileName: String? = null,
): AnalysisReport? {
    val invalidPaths = sourcePaths.filter { !Files.isDirectory(it) }
    if (invalidPaths.isNotEmpty()) {
        invalidPaths.forEach {
            System.err.println("Error: Source path does not exist or is not a directory: $it")
        }
        return null
    }

    // Step 1: Load the frozen JDBC spec
    print("Loading JDBC specification... ")
    val specMethods = JdbcSpecLoader().loadAll()
    if (specMethods.isEmpty()) {
        System.err.println("Error: No spec methods loaded. Check spec YAML files.")
        return null
    }
    val specByInterface = specMethods.groupBy { it.interfaceName }
    println("${specMethods.size} methods across ${specByInterface.size} interfaces")

    // Step 2: Parse source files
    print("Parsing source files... ")
    val parser = SourceParser(sourcePaths)
    val compilationUnits = parser.parseAll()
    val parseStats = parser.getParseStats()
    println(parseStats.summary())
    if (parseStats.failedFiles > 0) {
        parseStats.failures.forEach { (file, msg) ->
            System.err.println("  Warning: Failed to parse $file: $msg")
        }
    }
    if (compilationUnits.isEmpty()) {
        System.err.println("Error: No Java source files found in ${sourcePaths.joinToString()}")
        return null
    }
    warnIfNotPackageRoot(sourcePaths, compilationUnits)

    // Step 2.5: Resolve driver profile (auto-detect unless --profile given)
    val profile: DriverProfile? = try {
        ProfileResolver().resolve(compilationUnits, profileName)
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        return null
    }
    println(
        when {
            profile != null && profileName != null ->
                "Profile: ${profile.name} (${profile.displayName}, explicit)"
            profile != null -> "Profile: ${profile.name} (${profile.displayName}, auto-detected)"
            else -> "Profile: none (generic analyzer)"
        },
    )

    // Step 3: Resolve JDBC interface implementors.
    // --entry-class supports only the explicit "iface=class" form (validated in AnalyzeCommand);
    // CLI pins win over profile pins for one-off analyses.
    val explicitOverrides = entryClasses.associate { spec ->
        val eq = spec.indexOf('=')
        spec.substring(0, eq).trim() to spec.substring(eq + 1).trim()
    }
    val allOverrides = (profile?.entryClasses ?: emptyMap()) + explicitOverrides

    print("Resolving JDBC interface implementations... ")
    val implementors = JdbcInterfaceResolver().resolve(compilationUnits, allOverrides)
    println("${implementors.size} interfaces matched")

    // Step 4: Detect implementation status per method
    print("Analyzing implementation status... ")
    val detector = ImplementationDetector(
        stubHelpers = profile?.stubHelpers ?: emptyList(),
        extraStubExceptionClasses = profile?.stubExceptionClasses ?: emptyList(),
    )
    detector.registerCompilationUnits(compilationUnits)

    val interfaceResults = specByInterface.map { (interfaceName, methods) ->
        val classDecl = implementors[interfaceName]
        val implClassName = classDecl?.let { decl ->
            val pkg = decl.findCompilationUnit()
                .flatMap { it.packageDeclaration }
                .map { it.nameAsString }
                .orElse("")
            if (pkg.isEmpty()) decl.nameAsString else "$pkg.${decl.nameAsString}"
        }

        val methodResults = methods.map { specMethod ->
            if (classDecl != null) {
                MethodResult(
                    specMethod = specMethod,
                    status = detector.detect(specMethod, classDecl),
                    implementingClass = implClassName,
                )
            } else {
                MethodResult(specMethod = specMethod, status = ImplementationStatus.NotFound)
            }
        }

        InterfaceResult(
            interfaceName = interfaceName,
            implementingClass = implClassName,
            methods = methodResults,
        )
    }
    println("done")

    return AnalysisReport(
        driverName = driverName ?: detectDriverName(sourcePaths.first()),
        sourcePath = sourcePaths.joinToString(", ") { it.toAbsolutePath().toString() },
        analyzedAt = Instant.now(),
        interfaces = interfaceResults,
        profileUsed = profile?.name,
    )
}

/**
 * The symbol solver resolves inheritance chains only when each source path is a
 * package root (directory layout matches package declarations). Warn when it
 * isn't — analysis still runs, but entry-class detection quality degrades,
 * which historically caused large coverage swings (MySQL 47.2% vs 71.7%).
 */
internal fun warnIfNotPackageRoot(
    sourcePaths: List<Path>,
    compilationUnits: List<com.github.javaparser.ast.CompilationUnit>,
) {
    for (root in sourcePaths) {
        val absRoot = root.toAbsolutePath().normalize()
        val sample = compilationUnits.firstOrNull { cu ->
            val file = cu.storage.map { it.path.toAbsolutePath().normalize() }.orElse(null)
            file != null && file.startsWith(absRoot) && cu.packageDeclaration.isPresent
        } ?: continue
        val file = sample.storage.get().path.toAbsolutePath().normalize()
        val expectedRelDir = sample.packageDeclaration.get().nameAsString.replace('.', '/')
        val actualRelDir = absRoot.relativize(file.parent).toString().replace('\\', '/')
        if (actualRelDir != expectedRelDir) {
            System.err.println(
                "Warning: $root is not a package root (found package " +
                    "'${sample.packageDeclaration.get().nameAsString}' under '$actualRelDir'). " +
                    "Inheritance resolution may degrade; prefer passing the package root (e.g. src/main/java).",
            )
        }
    }
}

/**
 * Detect a display name from the deepest path segment that names a known driver.
 * Falls back to the directory basename. In CI, pass -n explicitly.
 */
internal fun detectDriverName(source: Path): String {
    val segments = source.toAbsolutePath().normalize().map { it.toString().lowercase() }.reversed()
    for (segment in segments) {
        when {
            "cubrid" in segment -> return "CUBRID JDBC"
            "mysql" in segment -> return "MySQL Connector/J"
            "mariadb" in segment -> return "MariaDB Connector/J"
            "postgresql" in segment || "pgjdbc" in segment -> return "PostgreSQL JDBC"
            "mssql" in segment || "sqlserver" in segment -> return "Microsoft SQL Server JDBC"
        }
    }
    return source.toAbsolutePath().fileName?.toString() ?: "Unknown"
}

/** Dispatch report outputs (console / json:<path>). */
internal fun dispatchOutputs(outputs: List<String>, report: AnalysisReport) {
    for (output in outputs) {
        when {
            output == "console" -> ConsoleReporter().report(report)
            output.startsWith("json:") -> {
                val path = Path.of(output.removePrefix("json:"))
                JsonReporter().report(report, path)
            }
            else -> System.err.println("Warning: Unknown output format: $output")
        }
    }
}

// ---------------------------------------------------------------------------
// analyze
// ---------------------------------------------------------------------------

@Command(
    name = "analyze",
    description = ["Analyze JDBC driver source code for spec compliance."],
    mixinStandardHelpOptions = true,
)
class AnalyzeCommand : Callable<Int> {

    @Parameters(
        index = "0..*",
        arity = "1..*",
        description = ["One or more source directories (package roots, e.g. driver/src/main/java)."],
    )
    lateinit var sources: List<Path>

    @Option(
        names = ["-o", "--output"],
        description = ["Output format: console, json:<path>. Can be specified multiple times."],
    )
    var outputs: List<String> = listOf("console")

    @Option(
        names = ["-n", "--driver-name"],
        description = ["Driver name for the report (auto-detected if not specified)."],
    )
    var driverName: String? = null

    @Option(
        names = ["--entry-class"],
        description = [
            "Pin the implementing class for a JDBC interface (overrides auto-detection).",
            "Format: --entry-class java.sql.Connection=com.example.ConnectionImpl",
            "Can be specified multiple times.",
        ],
    )
    var entryClasses: List<String> = emptyList()

    @Option(
        names = ["--profile"],
        description = [
            "Driver profile name (mssql, mysql, pgjdbc, mariadb, cubrid). Overrides auto-detection.",
        ],
    )
    var profileName: String? = null

    override fun call(): Int {
        println("JDBC Compliance Checker v$TOOL_VERSION")
        println("Source: ${sources.joinToString(", ")}")
        println()

        val badEntries = entryClasses.filter { '=' !in it }
        if (badEntries.isNotEmpty()) {
            badEntries.forEach {
                System.err.println("Error: --entry-class requires 'iface=class' format, got: $it")
            }
            return 1
        }

        val report = runAnalysis(
            sourcePaths = sources,
            driverName = driverName,
            entryClasses = entryClasses,
            profileName = profileName,
        ) ?: return 1
        println()
        dispatchOutputs(outputs, report)
        return 0
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(JdbcCheckerCommand()).execute(*args)
    System.exit(exitCode)
}
```

- [ ] **Step 5: 고아 파일 삭제**

```bash
git rm app/src/main/kotlin/com/jdbcchecker/cli/ExtractSpecCommand.kt \
       app/src/main/kotlin/com/jdbcchecker/cli/SourceResolver.kt \
       app/src/main/kotlin/com/jdbcchecker/git/GitCloneService.kt
git rm -r app/src/main/kotlin/com/jdbcchecker/spec/extractor \
          app/src/test/kotlin/com/jdbcchecker/spec/extractor
```

- [ ] **Step 6: 빌드 + 전체 테스트**

Run: `./gradlew :app:test --console=plain`
Expected: BUILD SUCCESSFUL. 디텍터 31개 테스트 그대로 통과. (컴파일 에러가 나면 삭제된 심볼을 참조하는 잔존 코드를 확인 — DiffCommand/CompareCommand는 이 태스크에서 Main.kt 재작성으로 이미 사라졌고, DiffReporter 등 리포터 파일은 Task 4에서 삭제하므로 아직 남아 있어도 컴파일에는 문제없다)

- [ ] **Step 7: 스모크 — 수치·옵션 동작 확인**

```bash
./gradlew :app:installDist -q
CHECKER=app/build/install/jdbc-checker/bin/jdbc-checker
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" | grep -E "Overall|Total:"
$CHECKER analyze /nonexistent 2>&1; echo "exit=$?"
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc --entry-class BadHintFormat 2>&1 | tail -1; echo "exit=$?"
```
Expected: (1) `docs/superpowers/plans/baseline-numbers.txt`와 동일한 수치, (2) `exit=1` + 에러 메시지, (3) `--entry-class requires 'iface=class' format` + `exit=1`

- [ ] **Step 8: 커밋**

```bash
git add -A app/src
git commit -m "refactor(cli): reduce CLI to a single analyze command

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 리포터·모델·빌드 정리

HTML 리포터, diff/compare 리포터와 비교 모델을 삭제하고 DiffEngine을 `computeDiff`만 남긴다(히스토리 델타가 재사용). 빌드에서 kapt/picocli-codegen/kotlinx-html/fatJar/snakeyaml을 제거한다.

**Files:**
- Delete: `app/src/main/kotlin/com/jdbcchecker/report/html/HtmlReporter.kt` (html 패키지 통째), `app/src/main/kotlin/com/jdbcchecker/report/console/DiffReporter.kt`, `app/src/main/kotlin/com/jdbcchecker/report/console/ComparisonReporter.kt`, `app/src/main/kotlin/com/jdbcchecker/report/json/DiffJsonReporter.kt`, `app/src/main/kotlin/com/jdbcchecker/model/DriverComparisonReport.kt`
- Modify: `app/src/main/kotlin/com/jdbcchecker/report/DiffEngine.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`

**Interfaces:**
- Produces: `computeDiff(baseline: AnalysisReport, current: AnalysisReport): DiffReport` 유지 (Task 8이 사용). `DiffReport`/`MethodDiff`/`MethodChangeType` 모델(`model/DiffResult.kt`) 유지.

- [ ] **Step 1: 파일 삭제**

```bash
git rm -r app/src/main/kotlin/com/jdbcchecker/report/html
git rm app/src/main/kotlin/com/jdbcchecker/report/console/DiffReporter.kt \
       app/src/main/kotlin/com/jdbcchecker/report/console/ComparisonReporter.kt \
       app/src/main/kotlin/com/jdbcchecker/report/json/DiffJsonReporter.kt \
       app/src/main/kotlin/com/jdbcchecker/model/DriverComparisonReport.kt
```

- [ ] **Step 2: DiffEngine 축소** — `DiffEngine.kt`에서 `computeComparison` 함수 전체(파일 후반부, `/**Computes a side-by-side comparison...*/` KDoc부터 파일 끝까지) 삭제. 미사용이 된 임포트 삭제: `DriverComparisonReport`, `DriverSummary`, `MethodComparisonRow`.

- [ ] **Step 3: build.gradle.kts 정리** — 파일 전체를 다음으로 교체:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // JavaParser + Symbol Solver
    implementation(libs.javaparser.symbol.solver)

    // CLI
    implementation(libs.picocli)

    // YAML/JSON
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)

    // Test
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.jdbcchecker.cli.MainKt"
    applicationName = "jdbc-checker"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jdbcchecker.cli.MainKt"
    }
}
```

- [ ] **Step 4: libs.versions.toml 정리** — `[versions]`에서 `snakeyaml`, `kotlinx-html` 줄 삭제; `[libraries]`에서 `picocli-codegen`, `snakeyaml`, `kotlinx-html` 줄 삭제.

- [ ] **Step 5: 빌드 + 전체 테스트 + 스모크**

```bash
./gradlew :app:test --console=plain
./gradlew :app:installDist -q
app/build/install/jdbc-checker/bin/jdbc-checker analyze \
  verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" | grep -E "Overall|Total:"
```
Expected: BUILD SUCCESSFUL, 수치는 `baseline-numbers.txt`와 동일

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "refactor(report): drop html/diff/compare reporters and build cruft

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 에러 처리·결정성 강화

cron 환경에서 히스토리에 구멍이 나지 않도록: 알 수 없는 `-o` → 분석 전 exit 1, JSON 출력 부모 디렉터리 자동 생성, 프로파일 핀 실효 시 하드 실패, 파일 순회 정렬, `--verbose` 유령 문구 제거.

**Files:**
- Modify: `app/src/main/kotlin/com/jdbcchecker/cli/Main.kt`, `app/src/main/kotlin/com/jdbcchecker/report/json/JsonReporter.kt`, `app/src/main/kotlin/com/jdbcchecker/resolver/JdbcInterfaceResolver.kt`, `app/src/main/kotlin/com/jdbcchecker/parser/SourceParser.kt`
- Create(Test): `app/src/test/kotlin/com/jdbcchecker/cli/OutputValidationTest.kt`, `app/src/test/kotlin/com/jdbcchecker/report/json/JsonReporterTest.kt`
- Modify(Test): `app/src/test/kotlin/com/jdbcchecker/detector/ImplementationDetectorTest.kt`는 건드리지 않음

**Interfaces:**
- Produces: `internal fun validateOutputs(outputs: List<String>): List<String>` (Main.kt; Task 9의 DashboardCommand는 사용하지 않음). `JdbcInterfaceResolver.resolve`는 핀 실효 시 `IllegalStateException`을 던짐.

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/com/jdbcchecker/cli/OutputValidationTest.kt` 생성:

```kotlin
package com.jdbcchecker.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutputValidationTest {

    @Test
    fun `accepts console and json outputs`() {
        assertThat(validateOutputs(listOf("console", "json:/tmp/r.json"))).isEmpty()
    }

    @Test
    fun `rejects unknown formats and empty json path`() {
        val errors = validateOutputs(listOf("html:/tmp/r.html", "json:", "bogus"))
        assertThat(errors).hasSize(3)
        assertThat(errors[0]).contains("html:/tmp/r.html")
    }
}
```

`app/src/test/kotlin/com/jdbcchecker/report/json/JsonReporterTest.kt` 생성:

```kotlin
package com.jdbcchecker.report.json

import com.jdbcchecker.model.AnalysisReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class JsonReporterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun emptyReport() = AnalysisReport(
        driverName = "X",
        sourcePath = "/tmp/src",
        analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
        interfaces = emptyList(),
    )

    @Test
    fun `creates missing parent directories before writing`() {
        val nested = tempDir.resolve("history/latest/x.json")
        JsonReporter().report(emptyReport(), nested)
        assertThat(nested).exists()
        assertThat(JsonReporter().loadReport(nested).driverName).isEqualTo("X")
    }
}
```

`app/src/test/kotlin/com/jdbcchecker/resolver/JdbcInterfaceResolverTest.kt` 생성:

```kotlin
package com.jdbcchecker.resolver

import com.github.javaparser.StaticJavaParser
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JdbcInterfaceResolverTest {

    @Test
    fun `stale entry-class pin fails hard instead of falling back`() {
        val cu = StaticJavaParser.parse(
            "package d; public class C implements java.sql.Wrapper { }",
        )
        assertThatThrownBy {
            JdbcInterfaceResolver().resolve(
                listOf(cu),
                overrides = mapOf("java.sql.Connection" to "d.MissingClass"),
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("d.MissingClass")
            .hasMessageContaining("java.sql.Connection")
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.jdbcchecker.cli.OutputValidationTest' --tests 'com.jdbcchecker.report.json.JsonReporterTest' --tests 'com.jdbcchecker.resolver.JdbcInterfaceResolverTest' --console=plain`
Expected: FAIL (validateOutputs 미정의 → 컴파일 에러; 이후 각 동작 미구현)

- [ ] **Step 3: 구현**

(a) `Main.kt`의 `dispatchOutputs` 함수 위에 추가:

```kotlin
/**
 * Validate -o specs BEFORE analysis runs, so a typo can't silently drop a
 * daily snapshot (previously unknown formats warned but exited 0).
 * Returns one error message per invalid spec; empty = all valid.
 */
internal fun validateOutputs(outputs: List<String>): List<String> =
    outputs.mapNotNull { output ->
        when {
            output == "console" -> null
            output.startsWith("json:") && output.removePrefix("json:").isNotBlank() -> null
            else -> "Unknown output format: $output (expected console or json:<path>)"
        }
    }
```

(b) `Main.kt`의 `dispatchOutputs` 내 `else ->` 분기를 다음으로 교체 (validateOutputs가 먼저 거르므로 도달 불가 방어선):

```kotlin
            else -> error("Unvalidated output format: $output")
```

(c) `AnalyzeCommand.call()`에서 `val badEntries = ...` 블록 바로 앞에 추가:

```kotlin
        val outputErrors = validateOutputs(outputs)
        if (outputErrors.isNotEmpty()) {
            outputErrors.forEach { System.err.println("Error: $it") }
            return 1
        }
```

(d) `JsonReporter.report`를 다음으로 교체 (+ `import java.nio.file.Files`):

```kotlin
    fun report(result: AnalysisReport, outputPath: Path) {
        outputPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        outputPath.writeText(mapper.writeValueAsString(result))
        println("JSON report written to: $outputPath")
    }
```

(e) `JdbcInterfaceResolver.resolve`의 override 루프에서 `else { System.err.println("Warning: ...") }` 분기를 다음으로 교체:

```kotlin
            } else {
                throw IllegalStateException(
                    "Entry class '$classFqcn' (pinned for $jdbcInterface) not found in parsed sources. " +
                        "Refusing to fall back to heuristics so daily numbers can't silently shift — " +
                        "update the profile/--entry-class pin.",
                )
            }
```

(f) `Main.kt`의 `runAnalysis`에서 resolver 호출을 try/catch로 감싼다:

```kotlin
    print("Resolving JDBC interface implementations... ")
    val implementors = try {
        JdbcInterfaceResolver().resolve(compilationUnits, allOverrides)
    } catch (e: IllegalStateException) {
        println()
        System.err.println("Error: ${e.message}")
        return null
    }
    println("${implementors.size} interfaces matched")
```

(g) `SourceParser.parseDirectory`의 `Files.walk(directory)` 다음에 `.sorted()` 추가 (타이브레이크 결정성):

```kotlin
    fun parseDirectory(directory: Path): List<CompilationUnit> =
        Files.walk(directory)
            .sorted()
            .asSequence()
            .filter { it.isRegularFile() && it.extension == "java" }
            .mapNotNull { parseFile(it) }
            .toList()
```

(h) `SourceParser`의 `ParseStats.summary()`에서 유령 플래그 문구 제거:

```kotlin
    fun summary(): String {
        val base = "$parsedFiles / $totalFiles files parsed"
        return if (failedFiles == 0) base else "$base ($failedFiles failed)"
    }
```

- [ ] **Step 4: 테스트 통과 + 전체 테스트 + 스모크**

```bash
./gradlew :app:test --console=plain
./gradlew :app:installDist -q
CHECKER=app/build/install/jdbc-checker/bin/jdbc-checker
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -o bogus 2>&1 | tail -1; echo "exit=$?"
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" | grep -E "Overall|Total:"
```
Expected: BUILD SUCCESSFUL; `-o bogus`는 분석 시작 전 `exit=1`; 수치는 baseline-numbers.txt와 동일 (정렬 도입으로 수치가 변하면 STOP — 타이브레이크가 실제로 갈렸다는 뜻이므로 어떤 인터페이스가 바뀌었는지 확인 후 프로파일 핀 보강을 검토)

- [ ] **Step 5: 커밋**

```bash
git add -A app gradle
git commit -m "fix(cli): fail fast on bad outputs and stale pins, deterministic walk

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 버전별 누적 커버리지 + --jdbc-version 필터

"≤4.2까지 몇 %"를 항상 보여주는 누적 커버리지를 모델·콘솔에 추가하고, `--jdbc-version`으로 스펙 상한을 걸 수 있게 한다 (기본: 전체 스펙).

**Files:**
- Modify: `app/src/main/kotlin/com/jdbcchecker/model/AnalysisResult.kt`, `app/src/main/kotlin/com/jdbcchecker/report/console/ConsoleReporter.kt`, `app/src/main/kotlin/com/jdbcchecker/cli/Main.kt`
- Create(Test): `app/src/test/kotlin/com/jdbcchecker/model/CumulativeCoverageTest.kt`
- Modify(Test): `app/src/test/kotlin/com/jdbcchecker/spec/JdbcSpecLoaderTest.kt`

**Interfaces:**
- Produces: `AnalysisReport.cumulativeCoverage: List<CumulativeCoverage>`; `data class CumulativeCoverage(version: JdbcVersion, total: Int, implemented: Int)` + `coveragePercent`. `runAnalysis`에 `maxVersion: JdbcVersion? = null` 파라미터. Task 8의 HistoryEntry가 cumulativeCoverage를 소비.

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/kotlin/com/jdbcchecker/model/CumulativeCoverageTest.kt` 생성:

```kotlin
package com.jdbcchecker.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CumulativeCoverageTest {

    private fun method(name: String, version: JdbcVersion, status: ImplementationStatus) = MethodResult(
        specMethod = MethodSignature("java.sql.Connection", name, emptyList(), "void", version),
        status = status,
    )

    @Test
    fun `cumulative coverage accumulates methods up to each boundary`() {
        val report = AnalysisReport(
            driverName = "X",
            sourcePath = "s",
            analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
            interfaces = listOf(
                InterfaceResult(
                    interfaceName = "java.sql.Connection",
                    implementingClass = "d.C",
                    methods = listOf(
                        method("a", JdbcVersion.V1_0, ImplementationStatus.FullyImplemented),
                        method("b", JdbcVersion.V1_0, ImplementationStatus.ThrowsUnsupported),
                        method("c", JdbcVersion.V4_2, ImplementationStatus.FullyImplemented),
                        method("d", JdbcVersion.V4_3, ImplementationStatus.NotFound),
                    ),
                ),
            ),
        )

        val cumulative = report.cumulativeCoverage
        // Boundaries only for versions present in the spec set: 1.0, 4.2, 4.3
        assertThat(cumulative.map { it.version })
            .containsExactly(JdbcVersion.V1_0, JdbcVersion.V4_2, JdbcVersion.V4_3)

        val v10 = cumulative[0]
        assertThat(v10.total).isEqualTo(2)
        assertThat(v10.implemented).isEqualTo(1)
        assertThat(v10.coveragePercent).isEqualTo(50.0)

        val v42 = cumulative[1]
        assertThat(v42.total).isEqualTo(3)
        assertThat(v42.implemented).isEqualTo(2)

        val v43 = cumulative[2]
        assertThat(v43.total).isEqualTo(4)
        assertThat(v43.implemented).isEqualTo(2)
    }
}
```

`JdbcSpecLoaderTest.kt`에 추가 (≤4.2 분모 고정 확인):

```kotlin
    @Test
    fun `spec-1 has 849 methods at or below JDBC 4-2`() {
        val upTo42 = loader.loadAll().filter { it.jdbcVersion.ordinal <= JdbcVersion.V4_2.ordinal }
        assertThat(upTo42).hasSize(849)
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.jdbcchecker.model.CumulativeCoverageTest' --tests 'com.jdbcchecker.spec.JdbcSpecLoaderTest' --console=plain`
Expected: FAIL — `cumulativeCoverage` 미정의(컴파일 에러). 849 테스트는 통과할 수 있음(스펙은 이미 동결됨 — 만약 실패하면 실제값 확인: 896−40(4.3+)−7(scope) = 849가 맞는지 `grep`으로 검산 후 STOP)

- [ ] **Step 3: 모델 구현** — `AnalysisResult.kt`의 `statusDistribution` getter 아래에 추가:

```kotlin
    /**
     * Cumulative coverage at each JDBC version boundary present in the spec set:
     * "of all methods introduced at or before version V, how many are implemented".
     * This is the number to watch while expanding toward a target version (e.g. 4.2).
     */
    val cumulativeCoverage: List<CumulativeCoverage>
        get() {
            val allMethods = interfaces.flatMap { it.methods }
            return JdbcVersion.entries
                .filter { v -> allMethods.any { it.specMethod.jdbcVersion == v } }
                .map { boundary ->
                    val upTo = allMethods.filter { it.specMethod.jdbcVersion.ordinal <= boundary.ordinal }
                    CumulativeCoverage(
                        version = boundary,
                        total = upTo.size,
                        implemented = upTo.count { it.status.isImplemented() },
                    )
                }
        }
```

파일 하단 `VersionCoverage` 아래에 추가:

```kotlin
/**
 * Cumulative coverage of all methods introduced at or before [version].
 */
data class CumulativeCoverage(
    val version: JdbcVersion,
    val total: Int,
    val implemented: Int,
) {
    val coveragePercent: Double
        get() = if (total == 0) 0.0 else (implemented.toDouble() / total) * 100.0
}
```

- [ ] **Step 4: 콘솔 출력** — `ConsoleReporter.kt`:

`report()`의 `printVersionBreakdown(result)` 다음 줄에 `printCumulativeCoverage(result)` 호출 추가, 그리고 `printVersionBreakdown` 함수 아래에 추가:

```kotlin
    private fun printCumulativeCoverage(result: AnalysisReport) {
        val separator = "-".repeat(70)
        println(separator)
        println("  Cumulative Coverage (all methods introduced at or before version):")
        println(separator)

        for (c in result.cumulativeCoverage) {
            val bar = progressBar(c.coveragePercent, 20)
            println("  <=%-4s  %s  %4d/%4d".format(c.version.display, bar, c.implemented, c.total))
        }
        println()
    }
```

- [ ] **Step 5: --jdbc-version 필터** — `Main.kt`:

(a) `runAnalysis` 시그니처에 파라미터 추가 (`profileName` 뒤):

```kotlin
    profileName: String? = null,
    maxVersion: com.jdbcchecker.model.JdbcVersion? = null,
```

(b) `runAnalysis`의 spec 로딩을 다음으로 교체:

```kotlin
    print("Loading JDBC specification... ")
    val allSpecMethods = JdbcSpecLoader().loadAll()
    val specMethods = if (maxVersion == null) {
        allSpecMethods
    } else {
        allSpecMethods.filter { it.jdbcVersion.ordinal <= maxVersion.ordinal }
    }
```

(c) `AnalyzeCommand`에 옵션 추가 (`profileName` 옵션 아래):

```kotlin
    @Option(
        names = ["--jdbc-version"],
        description = [
            "Only include spec methods introduced at or before this JDBC version (e.g. 4.2).",
            "Default: the full bundled spec (latest version).",
        ],
    )
    var jdbcVersion: String? = null
```

(d) `AnalyzeCommand.call()`에서 `val report = runAnalysis(` 앞에 추가하고 호출에 `maxVersion = maxVersion` 전달:

```kotlin
        val maxVersion = jdbcVersion?.let { requested ->
            com.jdbcchecker.model.JdbcVersion.fromString(requested) ?: run {
                System.err.println(
                    "Error: Unknown JDBC version: $requested " +
                        "(known: ${com.jdbcchecker.model.JdbcVersion.entries.joinToString { it.display }})",
                )
                return 1
            }
        }
```

- [ ] **Step 6: 전체 테스트 + 스모크**

```bash
./gradlew :app:test --console=plain
./gradlew :app:installDist -q
CHECKER=app/build/install/jdbc-checker/bin/jdbc-checker
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" | grep -A 12 "Cumulative"
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc --jdbc-version 4.2 | grep "Total:"
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc --jdbc-version 9.9 2>&1 | tail -1; echo "exit=$?"
```
Expected: 누적 표에 `<=4.2` 행 존재; `--jdbc-version 4.2`에서 `Total: 849 methods`; `9.9`는 `exit=1`

- [ ] **Step 7: 커밋**

```bash
git add -A app gradle
git commit -m "feat(model): cumulative version coverage and --jdbc-version filter

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 스냅샷 프로버넌스 (specVersion / toolVersion / sourceCommit)

모든 스냅샷 JSON에 "무엇으로 측정했는가"를 각인해, 히스토리에서 드라이버의 진전과 도구·스펙의 변화를 구분할 수 있게 한다.

**Files:**
- Modify: `app/src/main/kotlin/com/jdbcchecker/model/AnalysisResult.kt`, `app/src/main/kotlin/com/jdbcchecker/cli/Main.kt`, `app/src/main/kotlin/com/jdbcchecker/report/console/ConsoleReporter.kt`
- Create(Test): `app/src/test/kotlin/com/jdbcchecker/cli/ProvenanceTest.kt`

**Interfaces:**
- Consumes: `JdbcSpecLoader.SPEC_VERSION` (Task 2), `TOOL_VERSION` (Task 3).
- Produces: `AnalysisReport.specVersion: String` / `toolVersion: String` / `sourceCommit: String?` (JSON 직렬화 포함, 구버전 JSON은 기본값으로 역직렬화). `internal fun resolveSourceCommit(dir: Path): String?` (Main.kt). Task 8의 HistoryEntry가 세 필드를 소비.

- [ ] **Step 1: 실패하는 테스트 작성** — `app/src/test/kotlin/com/jdbcchecker/cli/ProvenanceTest.kt` 생성:

```kotlin
package com.jdbcchecker.cli

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.report.json.JsonReporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ProvenanceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `provenance fields round-trip through JSON`() {
        val report = AnalysisReport(
            driverName = "X",
            sourcePath = "s",
            analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
            interfaces = emptyList(),
            profileUsed = "cubrid",
            specVersion = "spec-1",
            toolVersion = TOOL_VERSION,
            sourceCommit = "a".repeat(40),
        )
        val path = tempDir.resolve("r.json")
        JsonReporter().report(report, path)
        val loaded = JsonReporter().loadReport(path)
        assertThat(loaded.specVersion).isEqualTo("spec-1")
        assertThat(loaded.toolVersion).isEqualTo(TOOL_VERSION)
        assertThat(loaded.sourceCommit).isEqualTo("a".repeat(40))
    }

    @Test
    fun `resolveSourceCommit returns a 40-hex hash inside a git repo`() {
        // This test runs inside the project repo, which is a git repository.
        val commit = resolveSourceCommit(Path.of("."))
        assertThat(commit).isNotNull().matches("[0-9a-f]{40}")
    }

    @Test
    fun `resolveSourceCommit returns null outside a git repo`() {
        assertThat(resolveSourceCommit(tempDir)).isNull()
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.jdbcchecker.cli.ProvenanceTest' --console=plain`
Expected: FAIL — `specVersion` 파라미터/`resolveSourceCommit` 미정의(컴파일 에러)

- [ ] **Step 3: 구현**

(a) `AnalysisResult.kt`의 `AnalysisReport` 생성자에 필드 추가 (`profileUsed` 뒤; 기본값 덕에 구버전 JSON도 역직렬화됨):

```kotlin
    val profileUsed: String? = null,
    /** Frozen spec identifier this snapshot was measured against (e.g. "spec-1"). */
    val specVersion: String = "",
    /** Tool version that produced this snapshot. */
    val toolVersion: String = "",
    /** `git rev-parse HEAD` of the analyzed source tree; null when not a git checkout. */
    val sourceCommit: String? = null,
```

(b) `Main.kt`의 `detectDriverName` 함수 아래에 추가:

```kotlin
/**
 * Best-effort `git rev-parse HEAD` of the repository containing [dir].
 * Returns null when git is unavailable or [dir] is not inside a work tree —
 * provenance is desirable but must never fail an analysis.
 */
internal fun resolveSourceCommit(dir: Path): String? = try {
    val process = ProcessBuilder("git", "-C", dir.toAbsolutePath().toString(), "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) output else null
} catch (e: Exception) {
    null
}
```

(c) `runAnalysis`의 `return AnalysisReport(` 블록에 세 인자 추가:

```kotlin
        profileUsed = profile?.name,
        specVersion = JdbcSpecLoader.SPEC_VERSION,
        toolVersion = TOOL_VERSION,
        sourceCommit = resolveSourceCommit(sourcePaths.first()),
```

(d) `ConsoleReporter.printHeader`의 `result.profileUsed?.let { ... }` 다음 줄에 추가:

```kotlin
        if (result.specVersion.isNotEmpty()) {
            val commit = result.sourceCommit?.let { "  Commit: ${it.take(10)}" } ?: ""
            println("  Spec: ${result.specVersion}  Tool: v${result.toolVersion}$commit")
        }
```

- [ ] **Step 4: 전체 테스트 + 스모크**

```bash
./gradlew :app:test --console=plain
./gradlew :app:installDist -q
app/build/install/jdbc-checker/bin/jdbc-checker analyze \
  verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" -o json:/tmp/prov.json
grep -E '"specVersion"|"toolVersion"|"sourceCommit"' /tmp/prov.json
```
Expected: `"specVersion" : "spec-1"`, `"toolVersion" : "2.0.0"`, sourceCommit은 40-hex (verification/sources/cubrid-jdbc가 git checkout이므로)

- [ ] **Step 5: 커밋**

```bash
git add -A app gradle
git commit -m "feat(report): stamp spec/tool/commit provenance into snapshots

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: HistoryRecorder + `--history <dir>`

`analyze --history <dir>`가 오늘의 리포트를 `latest/<slug>.json`과 비교해 델타를 뽑고, `history/<slug>.jsonl`에 한 줄 추가한 뒤 latest를 덮어쓴다. 같은 날 재실행은 그 날짜 줄을 교체하고, specVersion이 다르면 델타 대신 `specChanged=true`를 기록한다.

**Files:**
- Create: `app/src/main/kotlin/com/jdbcchecker/history/HistoryModels.kt`, `app/src/main/kotlin/com/jdbcchecker/history/HistoryRecorder.kt`
- Modify: `app/src/main/kotlin/com/jdbcchecker/cli/Main.kt`
- Test: `app/src/test/kotlin/com/jdbcchecker/history/HistoryRecorderTest.kt`

**Interfaces:**
- Consumes: `computeDiff` (Task 4 유지분), `AnalysisReport.cumulativeCoverage` (Task 6), 프로버넌스 필드 (Task 7), `createObjectMapper()` / `JsonReporter`.
- Produces (Task 9의 dashboard가 소비):
  - `data class HistoryEntry(date: String, driverName: String, specVersion: String, toolVersion: String, sourceCommit: String?, profileUsed: String?, overallPercent: Double, totalMethods: Int, implemented: Int, stub: Int, notFound: Int, cumulative: List<CumulativePoint>, specChanged: Boolean = false, changes: List<MethodChange> = emptyList())`
  - `data class CumulativePoint(version: String, implemented: Int, total: Int)`
  - `data class MethodChange(interfaceName: String, method: String, before: String, after: String)`
  - `class HistoryRecorder(historyDir: Path)` / `fun record(report: AnalysisReport, date: LocalDate = LocalDate.now()): HistoryEntry` / `companion fun slugOf(driverName: String): String`
  - 디렉터리 규약: `<historyDir>/history/<slug>.jsonl`, `<historyDir>/latest/<slug>.json`

- [ ] **Step 1: 실패하는 테스트 작성** — `app/src/test/kotlin/com/jdbcchecker/history/HistoryRecorderTest.kt` 생성:

```kotlin
package com.jdbcchecker.history

import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.ImplementationStatus
import com.jdbcchecker.model.InterfaceResult
import com.jdbcchecker.model.JdbcVersion
import com.jdbcchecker.model.MethodResult
import com.jdbcchecker.model.MethodSignature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

class HistoryRecorderTest {

    @TempDir
    lateinit var dir: Path

    private fun report(status: ImplementationStatus, specVersion: String = "spec-1"): AnalysisReport {
        val method = MethodResult(
            specMethod = MethodSignature("java.sql.Connection", "commit", emptyList(), "void", JdbcVersion.V1_0),
            status = status,
        )
        return AnalysisReport(
            driverName = "CUBRID JDBC",
            sourcePath = "/tmp/src",
            analyzedAt = Instant.parse("2026-07-05T00:00:00Z"),
            interfaces = listOf(InterfaceResult("java.sql.Connection", "d.C", listOf(method))),
            profileUsed = "cubrid",
            specVersion = specVersion,
            toolVersion = "2.0.0",
            sourceCommit = null,
        )
    }

    @Test
    fun `slug is stable and filesystem-safe`() {
        assertThat(HistoryRecorder.slugOf("CUBRID JDBC")).isEqualTo("cubrid-jdbc")
        assertThat(HistoryRecorder.slugOf("MySQL Connector/J")).isEqualTo("mysql-connector-j")
    }

    @Test
    fun `first run records an entry without changes and writes latest`() {
        val entry = HistoryRecorder(dir).record(report(ImplementationStatus.ThrowsUnsupported), LocalDate.parse("2026-07-05"))

        assertThat(entry.changes).isEmpty()
        assertThat(entry.specChanged).isFalse()
        assertThat(entry.overallPercent).isEqualTo(0.0)
        assertThat(dir.resolve("history/cubrid-jdbc.jsonl")).exists()
        assertThat(dir.resolve("latest/cubrid-jdbc.json")).exists()
        assertThat(Files.readAllLines(dir.resolve("history/cubrid-jdbc.jsonl"))).hasSize(1)
    }

    @Test
    fun `second run records status transitions as changes`() {
        val recorder = HistoryRecorder(dir)
        recorder.record(report(ImplementationStatus.ThrowsUnsupported), LocalDate.parse("2026-07-05"))
        val entry = recorder.record(report(ImplementationStatus.FullyImplemented), LocalDate.parse("2026-07-06"))

        assertThat(entry.changes).hasSize(1)
        assertThat(entry.changes[0].before).isEqualTo("THROWS_UNSUPPORTED")
        assertThat(entry.changes[0].after).isEqualTo("FULLY_IMPLEMENTED")
        assertThat(entry.overallPercent).isEqualTo(100.0)
        assertThat(Files.readAllLines(dir.resolve("history/cubrid-jdbc.jsonl"))).hasSize(2)
    }

    @Test
    fun `same-day rerun replaces that day's line`() {
        val recorder = HistoryRecorder(dir)
        recorder.record(report(ImplementationStatus.ThrowsUnsupported), LocalDate.parse("2026-07-05"))
        recorder.record(report(ImplementationStatus.FullyImplemented), LocalDate.parse("2026-07-05"))

        val lines = Files.readAllLines(dir.resolve("history/cubrid-jdbc.jsonl")).filter { it.isNotBlank() }
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("100.0")
    }

    @Test
    fun `spec version mismatch records specChanged without deltas`() {
        val recorder = HistoryRecorder(dir)
        recorder.record(report(ImplementationStatus.ThrowsUnsupported, specVersion = "spec-1"), LocalDate.parse("2026-07-05"))
        val entry = recorder.record(report(ImplementationStatus.FullyImplemented, specVersion = "spec-2"), LocalDate.parse("2026-07-06"))

        assertThat(entry.specChanged).isTrue()
        assertThat(entry.changes).isEmpty()
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.jdbcchecker.history.HistoryRecorderTest' --console=plain`
Expected: FAIL (history 패키지 미존재 → 컴파일 에러)

- [ ] **Step 3: 모델 구현** — `app/src/main/kotlin/com/jdbcchecker/history/HistoryModels.kt` 생성:

```kotlin
package com.jdbcchecker.history

/** Cumulative coverage at one version boundary, projected for the JSONL line. */
data class CumulativePoint(
    val version: String,
    val implemented: Int,
    val total: Int,
)

/** One method whose status changed since the previous recorded run. */
data class MethodChange(
    val interfaceName: String,
    val method: String,
    val before: String,
    val after: String,
)

/**
 * One line of `history/<slug>.jsonl` — the permanent, compact daily record.
 * Full per-method detail lives only in `latest/<slug>.json` (overwritten daily).
 */
data class HistoryEntry(
    val date: String,
    val driverName: String,
    val specVersion: String,
    val toolVersion: String,
    val sourceCommit: String?,
    val profileUsed: String?,
    val overallPercent: Double,
    val totalMethods: Int,
    val implemented: Int,
    val stub: Int,
    val notFound: Int,
    val cumulative: List<CumulativePoint>,
    val specChanged: Boolean = false,
    val changes: List<MethodChange> = emptyList(),
)
```

- [ ] **Step 4: 레코더 구현** — `app/src/main/kotlin/com/jdbcchecker/history/HistoryRecorder.kt` 생성:

```kotlin
package com.jdbcchecker.history

import com.fasterxml.jackson.databind.SerializationFeature
import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.model.MethodChangeType
import com.jdbcchecker.report.computeDiff
import com.jdbcchecker.report.json.JsonReporter
import com.jdbcchecker.report.json.createObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Records one driver's daily analysis into a history directory:
 *
 *   <historyDir>/history/<slug>.jsonl   one compact line per day, kept forever
 *   <historyDir>/latest/<slug>.json     full report, overwritten each run
 *
 * The delta in each line is computed against the previous `latest/` report
 * BEFORE overwriting it. When the spec version differs from the previous run,
 * no delta is computed (`specChanged=true`) — those diffs would be phantom.
 */
class HistoryRecorder(private val historyDir: Path) {

    private val lineMapper = createObjectMapper().copy().disable(SerializationFeature.INDENT_OUTPUT)
    private val jsonReporter = JsonReporter()

    fun record(report: AnalysisReport, date: LocalDate = LocalDate.now()): HistoryEntry {
        val slug = slugOf(report.driverName)
        val latestPath = historyDir.resolve("latest").resolve("$slug.json")
        val previous = if (Files.isRegularFile(latestPath)) jsonReporter.loadReport(latestPath) else null

        val specChanged = previous != null && previous.specVersion != report.specVersion
        val changes = if (previous != null && !specChanged) {
            computeDiff(previous, report).diffs
                .filter { it.change != MethodChangeType.UNCHANGED }
                .map { MethodChange(it.interfaceName, it.methodDisplayName, it.before.key, it.after.key) }
        } else {
            emptyList()
        }

        val entry = HistoryEntry(
            date = date.toString(),
            driverName = report.driverName,
            specVersion = report.specVersion,
            toolVersion = report.toolVersion,
            sourceCommit = report.sourceCommit,
            profileUsed = report.profileUsed,
            overallPercent = report.overallCoveragePercent,
            totalMethods = report.totalMethods,
            implemented = report.totalImplemented,
            stub = report.totalStub,
            notFound = report.totalNotFound,
            cumulative = report.cumulativeCoverage.map {
                CumulativePoint(it.version.display, it.implemented, it.total)
            },
            specChanged = specChanged,
            changes = changes,
        )

        val jsonlPath = historyDir.resolve("history").resolve("$slug.jsonl")
        Files.createDirectories(jsonlPath.parent)
        val kept = if (Files.exists(jsonlPath)) {
            Files.readAllLines(jsonlPath)
                .filter { it.isNotBlank() }
                .filter { lineMapper.readValue(it, HistoryEntry::class.java).date != entry.date }
        } else {
            emptyList()
        }
        Files.write(jsonlPath, kept + lineMapper.writeValueAsString(entry))

        jsonReporter.report(report, latestPath)
        return entry
    }

    companion object {
        /** "CUBRID JDBC" → "cubrid-jdbc": stable filename key per driver name. */
        fun slugOf(driverName: String): String =
            driverName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}
```

- [ ] **Step 5: CLI 연결** — `Main.kt`:

(a) `AnalyzeCommand`에 옵션 추가 (`jdbcVersion` 옵션 아래):

```kotlin
    @Option(
        names = ["--history"],
        description = [
            "History directory: appends a compact line to history/<driver>.jsonl",
            "(with deltas vs the previous run) and overwrites latest/<driver>.json.",
        ],
    )
    var historyDir: Path? = null
```

(b) `AnalyzeCommand.call()`의 `dispatchOutputs(outputs, report)` 다음에 추가:

```kotlin
        historyDir?.let { dir ->
            val entry = com.jdbcchecker.history.HistoryRecorder(dir).record(report)
            val slug = com.jdbcchecker.history.HistoryRecorder.slugOf(report.driverName)
            println("History recorded: ${dir.resolve("history").resolve("$slug.jsonl")} (${entry.changes.size} changes)")
        }
```

- [ ] **Step 6: 전체 테스트 + 스모크 (2회 실행으로 델타 0 확인)**

```bash
./gradlew :app:test --console=plain
./gradlew :app:installDist -q
rm -rf /tmp/jdbc-history && CHECKER=app/build/install/jdbc-checker/bin/jdbc-checker
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" --history /tmp/jdbc-history | tail -1
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" --history /tmp/jdbc-history | tail -1
wc -l /tmp/jdbc-history/history/cubrid-jdbc.jsonl
```
Expected: 두 번째 실행 `(0 changes)`; jsonl은 1줄(같은 날짜 교체)

- [ ] **Step 7: 커밋**

```bash
git add -A app gradle
git commit -m "feat(history): record daily history lines with method deltas

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: dashboard 커맨드 — 자기완결 트렌드 HTML

`jdbc-checker dashboard <history-dir> -o <html>`: 히스토리를 읽어 추이 차트(SVG+인라인 JS, 외부 요청 0) + 오늘의 비교표 + 변경 메서드 + 드라이버별 상세를 담은 HTML 한 장을 생성한다.

색상은 검증된 카테고리 팔레트(라이트 `#2a78d6 #1baf7a #eda100 #008300 #4a3aa7`, CVD ΔE 24.2 통과; 다크 변형 `#3987e5 #199e70 #c98500 #008300 #9085e9`)를 드라이버 slug 정렬 순서로 고정 배정한다. aqua/yellow의 대비 WARN은 범례·비교표(테이블 뷰)로 해소한다.

**Files:**
- Create: `app/src/main/kotlin/com/jdbcchecker/report/dashboard/DashboardData.kt`, `app/src/main/kotlin/com/jdbcchecker/report/dashboard/DashboardRenderer.kt`, `app/src/main/kotlin/com/jdbcchecker/cli/DashboardCommand.kt`
- Modify: `app/src/main/kotlin/com/jdbcchecker/cli/Main.kt` (subcommands 등록)
- Test: `app/src/test/kotlin/com/jdbcchecker/report/dashboard/DashboardRendererTest.kt`

**Interfaces:**
- Consumes: `HistoryEntry`/`CumulativePoint`/`MethodChange`, `HistoryRecorder` 디렉터리 규약 (Task 8), `JsonReporter.loadReport`, `AnalysisReport`.
- Produces: `data class DriverDashboardData(slug: String, entries: List<HistoryEntry>, latest: AnalysisReport?)`; `object DashboardData { fun load(historyDir: Path): List<DriverDashboardData> }`; `class DashboardRenderer { fun render(drivers: List<DriverDashboardData>): String }`; CLI `dashboard` 서브커맨드.

- [ ] **Step 1: 실패하는 테스트 작성** — `app/src/test/kotlin/com/jdbcchecker/report/dashboard/DashboardRendererTest.kt` 생성:

```kotlin
package com.jdbcchecker.report.dashboard

import com.jdbcchecker.history.CumulativePoint
import com.jdbcchecker.history.HistoryEntry
import com.jdbcchecker.history.MethodChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DashboardRendererTest {

    private fun entry(date: String, percent: Double, changes: List<MethodChange> = emptyList()) = HistoryEntry(
        date = date,
        driverName = "CUBRID JDBC",
        specVersion = "spec-1",
        toolVersion = "2.0.0",
        sourceCommit = "abc1234def567890abc1234def567890abc1234d",
        profileUsed = "cubrid",
        overallPercent = percent,
        totalMethods = 889,
        implemented = (889 * percent / 100).toInt(),
        stub = 300,
        notFound = 200,
        cumulative = listOf(CumulativePoint("4.2", 340, 849)),
        changes = changes,
    )

    @Test
    fun `renders a self-contained html with chart, table, and changes`() {
        val html = DashboardRenderer().render(
            listOf(
                DriverDashboardData(
                    slug = "cubrid-jdbc",
                    entries = listOf(
                        entry("2026-07-04", 40.0),
                        entry(
                            "2026-07-05",
                            41.0,
                            changes = listOf(
                                MethodChange("java.sql.Connection", "Connection.setSchema(String)", "NOT_FOUND", "FULLY_IMPLEMENTED"),
                            ),
                        ),
                    ),
                    latest = null,
                ),
            ),
        )

        assertThat(html).contains("<svg")
        assertThat(html).contains("CUBRID JDBC")
        assertThat(html).contains("Connection.setSchema(String)")
        assertThat(html).contains("41.0")
        // Self-contained: no external loads. (The SVG namespace URI string is
        // allowed — it is an identifier, not a network request.)
        assertThat(html).doesNotContain("<link", "<script src", "fetch(", "import(", "url(http")
        // Data embedded as JSON, not string-concatenated into JS
        assertThat(html).contains("application/json")
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.jdbcchecker.report.dashboard.DashboardRendererTest' --console=plain`
Expected: FAIL (dashboard 패키지 미존재 → 컴파일 에러)

- [ ] **Step 3: 데이터 로더 구현** — `app/src/main/kotlin/com/jdbcchecker/report/dashboard/DashboardData.kt` 생성:

```kotlin
package com.jdbcchecker.report.dashboard

import com.fasterxml.jackson.databind.SerializationFeature
import com.jdbcchecker.history.HistoryEntry
import com.jdbcchecker.model.AnalysisReport
import com.jdbcchecker.report.json.JsonReporter
import com.jdbcchecker.report.json.createObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/** One driver's full dashboard input: permanent history + optional latest full report. */
data class DriverDashboardData(
    val slug: String,
    val entries: List<HistoryEntry>,
    val latest: AnalysisReport?,
)

object DashboardData {

    /**
     * Load every driver found under `<historyDir>/history/*.jsonl`, sorted by
     * slug so palette slots stay stable across runs. Entries are sorted by date.
     */
    fun load(historyDir: Path): List<DriverDashboardData> {
        val lineMapper = createObjectMapper().copy().disable(SerializationFeature.INDENT_OUTPUT)
        val historySub = historyDir.resolve("history")
        if (!Files.isDirectory(historySub)) return emptyList()

        return Files.list(historySub).use { stream ->
            stream.asSequence()
                .filter { it.fileName.toString().endsWith(".jsonl") }
                .sortedBy { it.fileName.toString() }
                .map { file ->
                    val slug = file.fileName.toString().removeSuffix(".jsonl")
                    val entries = Files.readAllLines(file)
                        .filter { it.isNotBlank() }
                        .map { lineMapper.readValue(it, HistoryEntry::class.java) }
                        .sortedBy { it.date }
                    val latestPath = historyDir.resolve("latest").resolve("$slug.json")
                    val latest = if (Files.isRegularFile(latestPath)) JsonReporter().loadReport(latestPath) else null
                    DriverDashboardData(slug, entries, latest)
                }
                .filter { it.entries.isNotEmpty() }
                .toList()
        }
    }
}
```

- [ ] **Step 4: 렌더러 구현** — `app/src/main/kotlin/com/jdbcchecker/report/dashboard/DashboardRenderer.kt` 생성:

```kotlin
package com.jdbcchecker.report.dashboard

import com.fasterxml.jackson.databind.SerializationFeature
import com.jdbcchecker.report.json.createObjectMapper

/**
 * Renders the trend dashboard as a single self-contained HTML page:
 * data embedded as JSON, chart drawn by inline JS into an SVG, zero external
 * requests (works offline and on GitHub Pages alike).
 */
class DashboardRenderer {

    private val mapper = createObjectMapper().copy().disable(SerializationFeature.INDENT_OUTPUT)

    fun render(drivers: List<DriverDashboardData>): String {
        val payload = drivers.mapIndexed { i, d ->
            val last = d.entries.last()
            val prev = d.entries.dropLast(1).lastOrNull()
            mapOf(
                "name" to last.driverName,
                "slug" to d.slug,
                "color" to PALETTE_LIGHT[i % PALETTE_LIGHT.size],
                "colorDark" to PALETTE_DARK[i % PALETTE_DARK.size],
                "series" to d.entries.map { mapOf("date" to it.date, "percent" to it.overallPercent) },
                "last" to last,
                "prevPercent" to prev?.overallPercent,
                "interfaces" to (
                    d.latest?.interfaces?.map { iface ->
                        mapOf(
                            "name" to iface.interfaceName.substringAfterLast('.'),
                            "percent" to iface.coveragePercent,
                            "implemented" to iface.implemented,
                            "stub" to iface.stub,
                            "notFound" to iface.notFound,
                            "total" to iface.total,
                        )
                    } ?: emptyList()
                    ),
            )
        }
        // </script> inside the JSON would close the data block early — escape it.
        val json = mapper.writeValueAsString(payload).replace("</", "<\\/")
        return TEMPLATE.replace("__DATA__", json)
    }

    companion object {
        // Validated categorical palette (dataviz six-checks, light surface):
        // worst adjacent CVD deltaE 24.2. Slots assigned by sorted slug order.
        val PALETTE_LIGHT = listOf("#2a78d6", "#1baf7a", "#eda100", "#008300", "#4a3aa7")
        val PALETTE_DARK = listOf("#3987e5", "#199e70", "#c98500", "#008300", "#9085e9")
    }
}
```

같은 파일에 이어서 TEMPLATE 정의 (Kotlin raw string; JS는 `$` 문자를 쓰지 않도록 작성됨):

```kotlin
private val TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>JDBC API Coverage</title>
<style>
:root {
  --surface: #fcfcfb; --text: #1a1a19; --text-2: #57564e; --grid: #e4e3dd; --card: #f4f3ef;
}
@media (prefers-color-scheme: dark) {
  :root { --surface: #1a1a19; --text: #ffffff; --text-2: #c3c2b7; --grid: #3a3935; --card: #242320; }
}
* { box-sizing: border-box; }
body { margin: 2rem auto; max-width: 1000px; padding: 0 1rem; background: var(--surface);
       color: var(--text); font: 15px/1.55 system-ui, sans-serif; }
h1 { font-size: 1.5rem; margin-bottom: .25rem; }
h2 { font-size: 1.1rem; margin: 2rem 0 .5rem; }
.meta { color: var(--text-2); font-size: .85rem; margin-top: 0; }
#chart-wrap { position: relative; overflow-x: auto; }
#tip { position: absolute; pointer-events: none; background: var(--card); color: var(--text);
       border: 1px solid var(--grid); border-radius: 6px; padding: .4rem .6rem;
       font-size: .8rem; white-space: nowrap; }
#legend { display: flex; flex-wrap: wrap; gap: 1rem; margin-top: .5rem; font-size: .85rem; }
#legend .chip { display: inline-block; width: 12px; height: 12px; border-radius: 3px;
                margin-right: .35rem; vertical-align: -1px; }
table { border-collapse: collapse; width: 100%; font-size: .9rem; }
th, td { text-align: left; padding: .35rem .6rem; border-bottom: 1px solid var(--grid); }
th { color: var(--text-2); font-weight: 600; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
.up { color: #008300; } .down { color: #b3261e; } .flat { color: var(--text-2); }
details { margin: .5rem 0; } summary { cursor: pointer; font-weight: 600; }
.change { font-family: ui-monospace, monospace; font-size: .82rem; }
.spec-note { color: var(--text-2); font-size: .8rem; }
</style>
</head>
<body>
<h1>JDBC API Coverage</h1>
<p class="meta" id="meta"></p>

<h2>Trend</h2>
<div id="chart-wrap">
  <svg id="chart" viewBox="0 0 960 380" width="960" height="380" role="img" aria-label="Coverage trend by driver"></svg>
  <div id="tip" hidden></div>
</div>
<div id="legend"></div>

<h2>Today</h2>
<table id="today">
  <thead><tr><th>Driver</th><th class="num">Coverage</th><th class="num">vs prev</th>
  <th class="num">&le;4.2</th><th class="num">Impl</th><th class="num">Stub</th>
  <th class="num">Missing</th><th>Commit</th></tr></thead>
  <tbody></tbody>
</table>

<h2>Changes since previous run</h2>
<div id="changes"></div>

<h2>Per-driver detail</h2>
<div id="details"></div>

<script type="application/json" id="data">__DATA__</script>
<script>
"use strict";
var data = JSON.parse(document.getElementById("data").textContent);
var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
function colorOf(d) { return dark ? d.colorDark : d.color; }
function fmt(x) { return x == null ? "-" : x.toFixed(1); }

// meta line
var allDates = [];
data.forEach(function (d) { d.series.forEach(function (p) { allDates.push(p.date); }); });
allDates = Array.from(new Set(allDates)).sort();
document.getElementById("meta").textContent =
  "Generated " + allDates[allDates.length - 1] + " - spec " + data[0].last.specVersion +
  " - tool v" + data[0].last.toolVersion;

// ---- trend chart -----------------------------------------------------------
var W = 960, H = 380, PAD = { l: 48, r: 130, t: 16, b: 32 };
var iw = W - PAD.l - PAD.r, ih = H - PAD.t - PAD.b;
var svgNS = "http://www.w3.org/2000/svg";
var svg = document.getElementById("chart");
function el(tag, attrs, text) {
  var e = document.createElementNS(svgNS, tag);
  Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
  if (text) e.textContent = text;
  return e;
}
function x(i) { return PAD.l + (allDates.length < 2 ? iw / 2 : i * iw / (allDates.length - 1)); }
function y(p) { return PAD.t + ih - (p / 100) * ih; }
var css = getComputedStyle(document.documentElement);
var gridColor = css.getPropertyValue("--grid").trim();
var text2 = css.getPropertyValue("--text-2").trim();
[0, 20, 40, 60, 80, 100].forEach(function (v) {
  svg.appendChild(el("line", { x1: PAD.l, y1: y(v), x2: W - PAD.r, y2: y(v), stroke: gridColor, "stroke-width": 1 }));
  svg.appendChild(el("text", { x: PAD.l - 8, y: y(v) + 4, "text-anchor": "end", fill: text2, "font-size": 11 }, String(v)));
});
var first = allDates[0], mid = allDates[Math.floor((allDates.length - 1) / 2)], lastD = allDates[allDates.length - 1];
[[first, 0], [mid, Math.floor((allDates.length - 1) / 2)], [lastD, allDates.length - 1]].forEach(function (pair) {
  svg.appendChild(el("text", { x: x(pair[1]), y: H - 8, "text-anchor": "middle", fill: text2, "font-size": 11 }, pair[0]));
});
data.forEach(function (d) {
  var pts = d.series.map(function (p) { return x(allDates.indexOf(p.date)) + "," + y(p.percent); }).join(" ");
  svg.appendChild(el("polyline", { points: pts, fill: "none", stroke: colorOf(d), "stroke-width": 2,
    "stroke-linejoin": "round", "stroke-linecap": "round" }));
  var lastP = d.series[d.series.length - 1];
  var lx = x(allDates.indexOf(lastP.date)), ly = y(lastP.percent);
  svg.appendChild(el("circle", { cx: lx, cy: ly, r: 4, fill: colorOf(d) }));
  svg.appendChild(el("text", { x: lx + 8, y: ly + 4, fill: colorOf(d), "font-size": 12, "font-weight": 600 },
    d.name + " " + fmt(lastP.percent)));
});

// hover crosshair + tooltip
var tip = document.getElementById("tip");
var cross = el("line", { y1: PAD.t, y2: PAD.t + ih, stroke: text2, "stroke-width": 1, "stroke-dasharray": "3,3" });
cross.setAttribute("visibility", "hidden");
svg.appendChild(cross);
svg.addEventListener("mousemove", function (ev) {
  var rect = svg.getBoundingClientRect();
  var mx = (ev.clientX - rect.left) * (W / rect.width);
  var idx = 0, best = 1e9;
  allDates.forEach(function (d, i) { var dist = Math.abs(x(i) - mx); if (dist < best) { best = dist; idx = i; } });
  cross.setAttribute("x1", x(idx)); cross.setAttribute("x2", x(idx));
  cross.setAttribute("visibility", "visible");
  var lines = ["<b>" + allDates[idx] + "</b>"];
  data.forEach(function (d) {
    var p = d.series.filter(function (s) { return s.date === allDates[idx]; })[0];
    if (p) lines.push('<span class="chip" style="background:' + colorOf(d) + '"></span>' + d.name + " " + fmt(p.percent) + "%");
  });
  tip.innerHTML = lines.join("<br>");
  tip.hidden = false;
  tip.style.left = Math.min(x(idx) / W * 100, 75) + "%";
  tip.style.top = "10px";
});
svg.addEventListener("mouseleave", function () { tip.hidden = true; cross.setAttribute("visibility", "hidden"); });

// legend
var legend = document.getElementById("legend");
data.forEach(function (d) {
  var span = document.createElement("span");
  span.innerHTML = '<span class="chip" style="background:' + colorOf(d) + '"></span>' + d.name;
  legend.appendChild(span);
});

// ---- today table -----------------------------------------------------------
var tbody = document.querySelector("#today tbody");
data.forEach(function (d) {
  var last = d.last;
  var delta = d.prevPercent == null ? null : last.overallPercent - d.prevPercent;
  var deltaHtml = delta == null ? '<span class="flat">-</span>'
    : Math.abs(delta) < 0.05 ? '<span class="flat">=</span>'
    : delta > 0 ? '<span class="up">+' + delta.toFixed(1) + '</span>'
    : '<span class="down">' + delta.toFixed(1) + '</span>';
  var c42 = (last.cumulative || []).filter(function (c) { return c.version === "4.2"; })[0];
  var c42Html = c42 ? (100 * c42.implemented / c42.total).toFixed(1) : "-";
  var commit = last.sourceCommit ? last.sourceCommit.substring(0, 10) : "-";
  var tr = document.createElement("tr");
  tr.innerHTML = '<td><span class="chip" style="background:' + colorOf(d) + '"></span>' + last.driverName + "</td>" +
    '<td class="num"><b>' + fmt(last.overallPercent) + '%</b></td>' +
    '<td class="num">' + deltaHtml + "</td>" +
    '<td class="num">' + c42Html + "%</td>" +
    '<td class="num">' + last.implemented + "</td>" +
    '<td class="num">' + last.stub + "</td>" +
    '<td class="num">' + last.notFound + "</td>" +
    "<td><code>" + commit + "</code></td>";
  tbody.appendChild(tr);
});

// ---- changes ---------------------------------------------------------------
var changesDiv = document.getElementById("changes");
data.forEach(function (d) {
  var h = document.createElement("h3");
  h.textContent = d.name;
  h.style.fontSize = ".95rem";
  changesDiv.appendChild(h);
  if (d.last.specChanged) {
    var note = document.createElement("p");
    note.className = "spec-note";
    note.textContent = "Spec version changed - deltas not comparable for this run.";
    changesDiv.appendChild(note);
  }
  var changes = d.last.changes || [];
  if (changes.length === 0) {
    var p = document.createElement("p");
    p.className = "spec-note";
    p.textContent = "No changes.";
    changesDiv.appendChild(p);
  } else {
    changes.forEach(function (c) {
      var line = document.createElement("div");
      line.className = "change";
      line.textContent = c.method + ": " + c.before + " -> " + c.after;
      changesDiv.appendChild(line);
    });
  }
});

// ---- per-driver detail -----------------------------------------------------
var details = document.getElementById("details");
data.forEach(function (d) {
  var det = document.createElement("details");
  var sum = document.createElement("summary");
  sum.textContent = d.name;
  det.appendChild(sum);
  var cum = d.last.cumulative || [];
  var rows = cum.map(function (c) {
    return "<tr><td>&le;" + c.version + '</td><td class="num">' +
      (100 * c.implemented / c.total).toFixed(1) + '%</td><td class="num">' +
      c.implemented + "/" + c.total + "</td></tr>";
  }).join("");
  var ifaceRows = (d.interfaces || []).map(function (f) {
    return "<tr><td>" + f.name + '</td><td class="num">' + fmt(f.percent) + '%</td><td class="num">' +
      f.implemented + '</td><td class="num">' + f.stub + '</td><td class="num">' + f.notFound + "</td></tr>";
  }).join("");
  det.innerHTML += '<table><thead><tr><th>Cumulative</th><th class="num">Coverage</th><th class="num">Impl/Total</th></tr></thead><tbody>' + rows + "</tbody></table>";
  if (ifaceRows) {
    det.innerHTML += '<table style="margin-top:.75rem"><thead><tr><th>Interface</th><th class="num">Coverage</th><th class="num">Impl</th><th class="num">Stub</th><th class="num">Missing</th></tr></thead><tbody>' + ifaceRows + "</tbody></table>";
  }
  details.appendChild(det);
});
</script>
</body>
</html>
"""
```

참고: `document.createElementNS(svgNS, ...)` 때문에 SVG 네임스페이스 문자열(`http://www.w3.org/2000/svg`)이 JS에 존재한다. 이것은 식별자이지 네트워크 요청이 아니므로, Step 1 테스트의 자기완결 단정은 `http://` 대신 실제 로드 구문(`<link`, `<script src` 등)을 검사한다.

- [ ] **Step 5: CLI 커맨드** — `app/src/main/kotlin/com/jdbcchecker/cli/DashboardCommand.kt` 생성:

```kotlin
package com.jdbcchecker.cli

import com.jdbcchecker.report.dashboard.DashboardData
import com.jdbcchecker.report.dashboard.DashboardRenderer
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "dashboard",
    description = ["Render a self-contained trend dashboard HTML from a history directory."],
    mixinStandardHelpOptions = true,
)
class DashboardCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["History directory (contains history/*.jsonl and latest/*.json)."],
    )
    lateinit var historyDir: Path

    @Option(names = ["-o", "--output"], required = true, description = ["Output HTML file path."])
    lateinit var output: Path

    override fun call(): Int {
        val drivers = DashboardData.load(historyDir)
        if (drivers.isEmpty()) {
            System.err.println("Error: No history entries found under ${historyDir.resolve("history")}")
            return 1
        }
        output.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(output, DashboardRenderer().render(drivers))
        println("Dashboard written to: $output (${drivers.size} drivers)")
        return 0
    }
}
```

`Main.kt`의 subcommands 등록을 다음으로 교체:

```kotlin
    subcommands = [
        AnalyzeCommand::class,
        DashboardCommand::class,
    ],
```

- [ ] **Step 6: 전체 테스트 + 스모크 (실데이터 렌더 + 육안 확인)**

```bash
./gradlew :app:test --console=plain
./gradlew :app:installDist -q
CHECKER=app/build/install/jdbc-checker/bin/jdbc-checker
$CHECKER analyze verification/sources/cubrid-jdbc/src/jdbc -n "CUBRID JDBC" --history /tmp/jdbc-history
$CHECKER analyze verification/sources/pgjdbc/pgjdbc/src/main/java -n "PostgreSQL JDBC" --history /tmp/jdbc-history
$CHECKER dashboard /tmp/jdbc-history -o /tmp/jdbc-history/index.html
open /tmp/jdbc-history/index.html
```
Expected: `Dashboard written to: ... (2 drivers)`. 브라우저에서 확인: 꺾은선 2개(파랑/아쿠아), 축·범례·비교표·hover 툴팁 동작, 라벨 겹침 없음. (렌더 결과 육안 확인은 dataviz 절차의 필수 단계)

- [ ] **Step 7: 커밋**

```bash
git add -A app gradle
git commit -m "feat(dashboard): render self-contained trend dashboard html

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: 리포 청소 + 문서 통합

88MB 작업 산출물을 리포 밖으로 아카이브하고, 모순된 문서 4종+AGENT.md를 삭제하고, README를 새 CLI에 맞춰 재작성한다.

**Files:**
- Move(리포 밖): `verification/`, `jdbc-api/`, `jdbc_api/`, `0325_jdbc_api/`, `mysql_result/`, `app/report.json`, `jdk-sources/` → `../jdbc-checker-archive/`
- Delete: `docs/architecture.md`, `docs/technical-guide.md`, `docs/usage-patterns.md`, `docs/jdbc-spec-guide.md`, `AGENT.md`
- Modify: `README.md` (전면 재작성), `.gitignore`
- Keep: `docs/ANALYSIS_RULES.md`, `docs/superpowers/`

**Interfaces:**
- Consumes: 최종 CLI 표면 (Task 3~9).
- Produces: Task 11의 스모크가 사용할 아카이브 경로 `../jdbc-checker-archive/verification/sources/`.

- [ ] **Step 1: 아카이브 (삭제 아님 — 리포 밖 보존)**

```bash
mkdir -p ../jdbc-checker-archive
mv verification jdbc-api jdbc_api 0325_jdbc_api mysql_result ../jdbc-checker-archive/
mv app/report.json ../jdbc-checker-archive/
[ -d jdk-sources ] && mv jdk-sources ../jdbc-checker-archive/ || true
git status --short | grep '??' ; echo "leftover untracked above (should be none of the moved dirs)"
```

- [ ] **Step 2: 문서 삭제**

```bash
git rm docs/architecture.md docs/technical-guide.md docs/usage-patterns.md docs/jdbc-spec-guide.md AGENT.md
```

- [ ] **Step 3: .gitignore 재작성** — 파일 전체를 다음으로 교체:

```
# Gradle
.gradle
build
.kotlin

# IDE
.idea
*.iml
.vscode

# OS
.DS_Store
Thumbs.db

# Runtime
*.log
*.tmp

# Local analysis output (daily history lives on the gh-pages branch, not here)
reports/

# Study
docs/gradle-kotlin-study-roadmap.md
```

- [ ] **Step 4: README.md 재작성** — 파일 전체를 다음으로 교체:

````markdown
# JDBC Compliance Checker

JDBC 드라이버의 **소스 코드**를 정적 분석하여 JDBC API 구현 비율을 측정하는 CLI 도구입니다.
CUBRID JDBC의 스펙 확장(→ JDBC 4.2, 이후 4.3+) 진행률을 매일 추적하고, 다른 오픈소스
드라이버(MySQL/MariaDB/PostgreSQL/MSSQL)와 비교하는 것이 목적입니다.

- 메서드 본문을 분석해 7단계로 분류하고(구현/스텁/부재), 스텁(`throw UnsupportedOperationException`,
  기본값만 반환 등)은 구현으로 치지 않습니다. 분류 규칙: [docs/ANALYSIS_RULES.md](docs/ANALYSIS_RULES.md)
- 스펙은 JDK 26 기준 36개 인터페이스 **889 메서드로 동결**(`spec-1`)되어 있습니다. 스펙이나
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
````

- [ ] **Step 5: 전체 테스트 (아카이브가 테스트에 영향 없는지 확인)**

Run: `./gradlew :app:test --console=plain`
Expected: BUILD SUCCESSFUL (테스트는 verification/을 참조하지 않음)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "chore: archive working artifacts and consolidate docs

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: GitHub Actions daily.yml + gh-pages 부트스트랩 + 종단 검증

매일 5개 드라이버를 분석해 gh-pages에 게시하는 워크플로우를 추가하고, gh-pages 브랜치를 로컬에서 부트스트랩한다(푸시는 사용자 담당). 로컬에서 데일리 파이프라인 전체를 종단 검증한다.

**Files:**
- Create: `.github/workflows/daily.yml`
- Create(브랜치): `gh-pages` (orphan, `history/` `latest/` 빈 디렉터리)

**Interfaces:**
- Consumes: `analyze --history`, `dashboard` (Task 8~9), 검증된 드라이버별 패키지 루트 경로.
- Produces: gh-pages 게시 파이프라인. **사용자 액션 필요**: `main`·`gh-pages` 푸시 및 Pages 설정.

- [ ] **Step 1: 워크플로우 작성** — `.github/workflows/daily.yml` 생성:

```yaml
name: daily-coverage

on:
  schedule:
    - cron: '0 0 * * *'   # 00:00 UTC = 09:00 KST
  workflow_dispatch:

permissions:
  contents: write

jobs:
  coverage:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout checker
        uses: actions/checkout@v4
        with:
          path: checker

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build checker
        working-directory: checker
        run: ./gradlew :app:installDist

      # 타 드라이버는 default 브랜치 최신을 추적한다. 스냅샷에 commit hash가
      # 각인되므로 수치 변동의 원인(드라이버 변경)을 추적할 수 있다.
      - name: Checkout drivers
        run: |
          git clone --depth 1 https://github.com/CUBRID/cubrid-jdbc.git drivers/cubrid-jdbc
          git clone --depth 1 https://github.com/pgjdbc/pgjdbc.git drivers/pgjdbc
          git clone --depth 1 https://github.com/mysql/mysql-connector-j.git drivers/mysql-connector-j
          git clone --depth 1 https://github.com/mariadb-corporation/mariadb-connector-j.git drivers/mariadb-connector-j
          git clone --depth 1 https://github.com/microsoft/mssql-jdbc.git drivers/mssql-jdbc

      - name: Checkout history (gh-pages)
        uses: actions/checkout@v4
        with:
          ref: gh-pages
          path: site

      # 소스 경로는 2026-05-21 검증 캠페인과 동일한 경로(수치 연속성).
      # mysql은 멀티 모듈 루트를 통째로 넘기고 프로파일 핀에 의존한다.
      - name: Analyze drivers
        run: |
          CHECKER=checker/app/build/install/jdbc-checker/bin/jdbc-checker
          "$CHECKER" analyze drivers/cubrid-jdbc/src/jdbc              -n "CUBRID JDBC"                --history site
          "$CHECKER" analyze drivers/pgjdbc/pgjdbc/src/main/java       -n "PostgreSQL JDBC"            --history site
          "$CHECKER" analyze drivers/mysql-connector-j                 -n "MySQL Connector/J"          --history site
          "$CHECKER" analyze drivers/mariadb-connector-j/src/main/java -n "MariaDB Connector/J"        --history site
          "$CHECKER" analyze drivers/mssql-jdbc/src/main/java          -n "Microsoft SQL Server JDBC"  --history site

      - name: Render dashboard
        run: checker/app/build/install/jdbc-checker/bin/jdbc-checker dashboard site -o site/index.html

      - name: Publish to gh-pages
        working-directory: site
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add -A
          git commit -m "coverage: $(date -u +%F)" || echo "nothing to commit"
          git push origin gh-pages
```

- [ ] **Step 2: gh-pages 브랜치 부트스트랩 (로컬만, 푸시 금지)**

```bash
git worktree add ../jdbc-checker-ghpages HEAD
cd ../jdbc-checker-ghpages
git checkout --orphan gh-pages
git rm -rfq .
mkdir -p history latest
touch history/.gitkeep latest/.gitkeep
git add -A
git commit -m "chore: bootstrap gh-pages history

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
cd -
git worktree remove ../jdbc-checker-ghpages
git branch --list gh-pages
```
Expected: 마지막 명령이 `gh-pages` 출력

- [ ] **Step 3: 로컬 종단 검증 (데일리 파이프라인 시뮬레이션)**

```bash
./gradlew :app:installDist -q
CHECKER=app/build/install/jdbc-checker/bin/jdbc-checker
rm -rf /tmp/e2e-site && mkdir -p /tmp/e2e-site/history /tmp/e2e-site/latest
$CHECKER analyze ../jdbc-checker-archive/verification/sources/cubrid-jdbc/src/jdbc \
  -n "CUBRID JDBC" --history /tmp/e2e-site
$CHECKER analyze ../jdbc-checker-archive/verification/sources/mysql-connector-j \
  -n "MySQL Connector/J" --history /tmp/e2e-site
$CHECKER dashboard /tmp/e2e-site -o /tmp/e2e-site/index.html
python3 -c "
import json
for slug in ['cubrid-jdbc', 'mysql-connector-j']:
    d = json.load(open(f'/tmp/e2e-site/latest/{slug}.json'))
    print(slug, round(d['overallCoveragePercent'], 1), d['specVersion'], d['toolVersion'])
"
open /tmp/e2e-site/index.html
```
Expected: cubrid 수치가 `docs/superpowers/plans/baseline-numbers.txt`와 일치, mysql은 70.4 ± (스코프 정리 −7 효과) 수준, 둘 다 `spec-1 2.0.0`. 대시보드 육안 확인.

- [ ] **Step 4: 최종 전체 테스트 + 커밋**

```bash
./gradlew :app:test --console=plain
git add .github
git commit -m "ci: add daily coverage workflow publishing to gh-pages

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 5: 사용자 안내 출력 (푸시는 사용자 담당)**

작업 완료 보고에 다음을 반드시 포함:
1. `git push origin main gh-pages` 는 사용자가 직접 실행해야 함
2. GitHub 리포 Settings → Pages → Source: `gh-pages` 브랜치 / root 설정
3. 첫 실행은 Actions 탭에서 `daily-coverage` → Run workflow로 수동 트리거하여 확인
4. CUBRID의 측정 대상 브랜치를 default가 아닌 다른 브랜치로 바꾸려면 daily.yml의 clone에 `-b <branch>` 추가

---

## Self-Review 결과 (플랜 작성 시 수행)

- **스펙 커버리지**: 스펙 §4(CLI)→Task 3/6/8/9, §5(지표)→Task 2/6, §6(프로버넌스)→Task 7, §7(히스토리)→Task 8, §8(대시보드)→Task 9, §9(Actions)→Task 11, §10(에러 처리)→Task 5, §11(제거)→Task 3/4/10, §12(유지)→Global Constraints(규칙 동결)+각 태스크 스모크, §13(순서)→Task 1~11 순서, §14(테스트)→각 태스크 TDD 단계, §15(범위 외)→어느 태스크도 detector/resolver 판정 로직을 변경하지 않음.
- **자리표시자 스캔**: 통과 (모든 코드 완전 기재; Task 2 Step 4·Task 6 Step 2의 "실패 시 STOP" 분기는 검증 가드이지 TBD가 아님).
- **타입 일관성**: `HistoryEntry`(Task 8 정의 ↔ Task 9 소비), `CumulativeCoverage`(Task 6 ↔ Task 8 `cumulative` 프로젝션), `runAnalysis` 시그니처 진화(Task 3 → Task 6 파라미터 추가) 상호 참조 확인 완료.
