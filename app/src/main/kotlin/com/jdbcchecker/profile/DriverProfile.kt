package com.jdbcchecker.profile

import com.fasterxml.jackson.annotation.JsonAlias

/**
 * A driver-specific tuning profile that augments the generic analyzer with
 * knowledge that can't be reliably inferred by heuristics — for example,
 * MSSQL's `SQLServerException.throwNotSupportedException(...)` helper that
 * itself throws but doesn't contain the `throw` keyword textually.
 *
 * Profiles are loaded from bundled YAML resources or from a user-supplied
 * path via `--profile-file`. Selection precedence is managed by
 * [ProfileResolver].
 *
 * Profile YAML is intentionally minimal: each section is optional and
 * augments rather than replaces the generic analyzer's behavior, so a profile
 * declaring only `detectBy` (e.g. `cubrid.yaml`) is valid and useful as
 * driver-identification metadata.
 */
data class DriverProfile(
    /** Unique identifier (e.g., "mssql"). Selected by `--profile <name>`. */
    val name: String,

    /** Human-readable name shown in reports (e.g., "Microsoft SQL Server JDBC"). */
    val displayName: String,

    /**
     * Source-code patterns used to auto-detect this profile when the user
     * does not pass `--profile` / `--profile-file` / `--no-profile`.
     */
    val detectBy: DetectionRule = DetectionRule(),

    /**
     * Explicit JDBC interface → implementing class FQN mapping. Bypasses
     * [com.jdbcchecker.resolver.JdbcInterfaceResolver]'s auto-detection for
     * the listed interfaces. Unlisted interfaces fall back to auto-detection.
     *
     * Example:
     *   "java.sql.Connection" → "com.microsoft.sqlserver.jdbc.SQLServerConnection43"
     */
    val entryClasses: Map<String, String> = emptyMap(),

    /**
     * Driver-specific helper-method calls that themselves throw — the
     * detector treats a method whose body is "setup/logging + such a call"
     * as a stub, even though the body has no `throw` keyword.
     *
     * Match is by simple textual `contains` on the call expression's source.
     */
    val stubHelpers: List<StubHelper> = emptyList(),

    /**
     * Driver-specific exception class names that signal a stub when thrown,
     * even though they don't match the generic detector's "*SQL*Exception"
     * regex. Augments [com.jdbcchecker.detector.ImplementationDetector]'s
     * built-in stub-exception list at runtime.
     */
    val stubExceptionClasses: List<String> = emptyList(),
)

/**
 * Patterns used by [ProfileResolver] to pick a profile automatically when
 * the user didn't name one explicitly. A profile matches when ANY of its
 * `packagePrefix` strings is a prefix of ANY parsed compilation unit's
 * package declaration.
 */
data class DetectionRule(
    val packagePrefix: List<String> = emptyList(),
)

/**
 * Declares a driver-specific helper method that throws an exception. The
 * detector treats a method whose terminal statement (after setup/logging)
 * is such a call as a stub of the given [classify] type.
 *
 * The match is textual `contains`, so `callPattern` should be a unique
 * fragment of the call expression — typically `ClassName.staticMethodName`
 * or `someAccessor().instanceMethodName`. Keep it specific enough to avoid
 * unrelated method calls.
 */
data class StubHelper(
    val callPattern: String,
    @JsonAlias("classify_as")
    val classify: ClassifyAs = ClassifyAs.THROWS_SQL_EXCEPTION,
)

/** Stub category used by [StubHelper]. Maps to ImplementationStatus values. */
enum class ClassifyAs {
    THROWS_UNSUPPORTED,
    THROWS_SQL_EXCEPTION,
}
