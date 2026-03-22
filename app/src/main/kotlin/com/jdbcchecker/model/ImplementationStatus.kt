package com.jdbcchecker.model

/**
 * Represents how a JDBC method is implemented in the driver source code.
 *
 * Level 1 (MVP): NOT_FOUND, STUB, IMPLEMENTED
 * Level 2 (v0.2): Full detail including throw type, default returns, delegation
 */
sealed interface ImplementationStatus {

    /** Method does not exist in the driver source */
    data object NotFound : ImplementationStatus

    // --- Level 1: Stub variants (grouped as STUB for Level 1 reporting) ---

    /** Method body throws UnsupportedOperationException */
    data object ThrowsUnsupported : ImplementationStatus

    /** Method body throws SQLException with "not supported" message */
    data object ThrowsSqlException : ImplementationStatus

    /** Method body only returns a default value (null, 0, false, "") */
    data object ReturnsDefault : ImplementationStatus

    // --- Level 1: Implemented variants ---

    /** Method delegates to another method without additional logic */
    data object Delegates : ImplementationStatus

    /** Method has partial implementation (some branches throw/return default) */
    data object Partial : ImplementationStatus

    /** Method has full implementation */
    data object FullyImplemented : ImplementationStatus

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
