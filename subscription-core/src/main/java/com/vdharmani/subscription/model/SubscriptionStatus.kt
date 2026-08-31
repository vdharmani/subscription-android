package com.vdharmani.subscription.model

/**
 * Lifecycle state of a single [Entitlement].
 *
 * Entitlement is driven by **state**, not by "a subscription record exists" —
 * a cancelled-but-unexpired subscription still grants access, and a
 * subscription in account hold does not, even though both look like a live row.
 * [grantsAccess] is the single place that decision is encoded; gate your UI on
 * it rather than re-deriving the rules per screen.
 *
 * | State | Access | Recoverable by the user |
 * |---|---|---|
 * | [ACTIVE] | ✅ | — |
 * | [CANCELLED] | ✅ until the period ends | Resubscribe |
 * | [IN_GRACE_PERIOD] | ✅ | Fix payment method |
 * | [ON_HOLD] | ❌ | Fix payment method |
 * | [PAUSED] | ❌ | Resume in the Play Store |
 * | [EXPIRED] | ❌ | Resubscribe |
 * | [REFUNDED] | ❌ | Resubscribe |
 */
enum class SubscriptionStatus(
    /** Whether this state entitles the user to paid features. */
    val grantsAccess: Boolean,
) {
    /** Paid, current, and set to renew. */
    ACTIVE(grantsAccess = true),

    /**
     * Auto-renew is off but the paid period has not run out yet. **Not**
     * expired — revoking here bills the user for time they cannot use.
     */
    CANCELLED(grantsAccess = true),

    /**
     * A charge failed and the store is retrying. Access is retained for the
     * length of the grace period; a recovery here does **not** move the
     * renewal date.
     */
    IN_GRACE_PERIOD(grantsAccess = true),

    /**
     * The grace period ran out with the payment still failing. Access is
     * revoked but the subscription is recoverable — Play's hold defaults to
     * 60 days minus the configured grace period, so a user can sit here for
     * weeks. A recovery from hold **does** reset the renewal date.
     */
    ON_HOLD(grantsAccess = false),

    /**
     * User-initiated pause, scheduled to resume automatically. Google Play
     * only; there is no App Store equivalent.
     */
    PAUSED(grantsAccess = false),

    /** The paid period ended and was not renewed. */
    EXPIRED(grantsAccess = false),

    /**
     * The purchase was refunded or revoked. Final, not a cancellation — pull
     * access immediately rather than running out the period.
     */
    REFUNDED(grantsAccess = false),
    ;

    /** `true` for states the user can recover from without a new purchase. */
    val isRecoverable: Boolean
        get() = this == IN_GRACE_PERIOD || this == ON_HOLD || this == PAUSED
}
