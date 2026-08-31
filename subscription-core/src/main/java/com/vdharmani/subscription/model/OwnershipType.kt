package com.vdharmani.subscription.model

/** How the user came to hold an [Entitlement]. */
enum class OwnershipType {
    /** The current store account bought it. */
    PURCHASED,

    /** Shared with the user through Family Sharing — they cannot manage it. */
    FAMILY_SHARED,

    /** The store did not report an ownership type. */
    UNKNOWN,
}
