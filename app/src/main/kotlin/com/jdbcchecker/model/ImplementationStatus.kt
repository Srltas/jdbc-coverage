package com.jdbcchecker.model

/**
 * Represents how a JDBC method is implemented in the driver source code.
 *
 * Level 1 (MVP): NOT_FOUND, STUB, IMPLEMENTED
 * Level 2 (v0.2): Full detail including throw type, default returns, delegation
 */
sealed interface ImplementationStatus {

    /** Human-readable label for Level 2 display */
    val label: String

    /** Serialization key for JSON output/input */
    val key: String

    /** Method does not exist in the driver source */
    data object NotFound : ImplementationStatus {
        override val label = "Not Found"
        override val key = "NOT_FOUND"
    }

    // --- Stub variants (grouped as STUB for Level 1 reporting) ---

    /** Method body throws UnsupportedOperationException */
    data object ThrowsUnsupported : ImplementationStatus {
        override val label = "Throws Unsupported"
        override val key = "THROWS_UNSUPPORTED"
    }

    /** Method body throws SQLException with "not supported" message */
    data object ThrowsSqlException : ImplementationStatus {
        override val label = "Throws SQLException"
        override val key = "THROWS_SQLEXCEPTION"
    }

    /** Method body only returns a default value (null, 0, false, "") */
    data object ReturnsDefault : ImplementationStatus {
        override val label = "Returns Default"
        override val key = "RETURNS_DEFAULT"
    }

    // --- Implemented variants ---

    /** Method delegates to another method without additional logic */
    data object Delegates : ImplementationStatus {
        override val label = "Delegates"
        override val key = "DELEGATES"
    }

    /** Method has partial implementation (some branches throw/return default) */
    data object Partial : ImplementationStatus {
        override val label = "Partial"
        override val key = "PARTIAL"
    }

    /** Method has full implementation */
    data object FullyImplemented : ImplementationStatus {
        override val label = "Fully Implemented"
        override val key = "FULLY_IMPLEMENTED"
    }

    /** Level 1 check: is this status considered "implemented"? */
    fun isImplemented(): Boolean = when (this) {
        is Delegates, is Partial, is FullyImplemented -> true
        else -> false
    }

    /** Level 1 check: is this status considered a "stub"? */
    fun isStub(): Boolean = when (this) {
        is ThrowsUnsupported, is ThrowsSqlException, is ReturnsDefault -> true
        else -> false
    }

    /**
     * Numeric score used to determine improvement/regression direction.
     * Higher value = better implementation.
     */
    fun score(): Int = when (this) {
        is NotFound -> 0
        is ThrowsUnsupported, is ThrowsSqlException, is ReturnsDefault -> 1
        is Delegates -> 2
        is Partial -> 3
        is FullyImplemented -> 4
    }

    companion object {
        /** Level 1 classification: collapse detailed status into 3 categories */
        fun ImplementationStatus.toLevel1(): Level1Status = when (this) {
            is NotFound -> Level1Status.NOT_FOUND
            is ThrowsUnsupported, is ThrowsSqlException, is ReturnsDefault -> Level1Status.STUB
            is Delegates, is Partial, is FullyImplemented -> Level1Status.IMPLEMENTED
        }

        /** Deserialize from serialization key */
        fun fromKey(key: String): ImplementationStatus = when (key) {
            "NOT_FOUND" -> NotFound
            "THROWS_UNSUPPORTED" -> ThrowsUnsupported
            "THROWS_SQLEXCEPTION" -> ThrowsSqlException
            "RETURNS_DEFAULT" -> ReturnsDefault
            "DELEGATES" -> Delegates
            "PARTIAL" -> Partial
            "FULLY_IMPLEMENTED" -> FullyImplemented
            else -> throw IllegalArgumentException("Unknown ImplementationStatus key: $key")
        }
    }
}

enum class Level1Status(val display: String) {
    NOT_FOUND("Not Found"),
    STUB("Stub"),
    IMPLEMENTED("Implemented"),
}
