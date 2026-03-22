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

    /** Method does not exist in the driver source */
    data object NotFound : ImplementationStatus { override val label = "Not Found" }

    // --- Stub variants (grouped as STUB for Level 1 reporting) ---

    /** Method body throws UnsupportedOperationException */
    data object ThrowsUnsupported : ImplementationStatus { override val label = "Throws Unsupported" }

    /** Method body throws SQLException with "not supported" message */
    data object ThrowsSqlException : ImplementationStatus { override val label = "Throws SQLException" }

    /** Method body only returns a default value (null, 0, false, "") */
    data object ReturnsDefault : ImplementationStatus { override val label = "Returns Default" }

    // --- Implemented variants ---

    /** Method delegates to another method without additional logic */
    data object Delegates : ImplementationStatus { override val label = "Delegates" }

    /** Method has partial implementation (some branches throw/return default) */
    data object Partial : ImplementationStatus { override val label = "Partial" }

    /** Method has full implementation */
    data object FullyImplemented : ImplementationStatus { override val label = "Fully Implemented" }

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

    companion object {
        /** Level 1 classification: collapse detailed status into 3 categories */
        fun ImplementationStatus.toLevel1(): Level1Status = when (this) {
            is NotFound -> Level1Status.NOT_FOUND
            is ThrowsUnsupported, is ThrowsSqlException, is ReturnsDefault -> Level1Status.STUB
            is Delegates, is Partial, is FullyImplemented -> Level1Status.IMPLEMENTED
        }
    }
}

enum class Level1Status(val display: String) {
    NOT_FOUND("Not Found"),
    STUB("Stub"),
    IMPLEMENTED("Implemented"),
}
