package com.vdharmani.subscription.model

/**
 * A single entitlement held by the current user, active or not.
 *
 * Entitlements are the "permissions" your app grants based on a purchase
 * (e.g. `"premium"`, `"pro_features"`). They map to one or more product ids.
 * The set of entitlements is what most apps actually use to gate UI; the
 * underlying [Receipt] is the verifiable record.
 *
 * **Gate on [grantsAccess], not on the presence of an entitlement.** A
 * subscription in account hold or paused still has an entitlement record, and
 * a cancelled-but-unexpired one still deserves access. [status] carries the
 * full state; see [SubscriptionStatus].
 */
data class Entitlement(
    /** Stable identifier (e.g. `"premium"`). Configure these in your provider dashboard. */
    val identifier: String,

    /**
     * Store product id that granted this entitlement most recently, without a
     * base-plan suffix. When that product sells several base plans, this is the
     * same string for all of them — [basePlanId] is what tells them apart, and
     * [id] is the pair in the form `purchase` and `changeSubscription` take.
     */
    val productId: String,

    /**
     * When the granting purchase was made (unix seconds). `null` if the
     * provider didn't report a date — distinct from epoch 0, which used to be
     * the fallback and led to confusing "1970-01-01" purchase dates.
     */
    val purchasedAtSeconds: Long?,

    /** Unix-seconds expiry. `null` for non-expiring (lifetime / non-consumable) entitlements. */
    val expiresAtSeconds: Long?,

    /**
     * `true` if the subscription is set to renew at the period boundary.
     * `false` after the user cancels but before the entitlement actually expires —
     * still active, but no future charge.
     */
    val willRenew: Boolean,

    /** `true` while the user is in the billing grace period (last charge failed). */
    val isInGracePeriod: Boolean,

    /**
     * Base plan the user is actually on (e.g. `"yearly-autorenew"`), or null
     * when the store has no such concept or does not report it.
     *
     * Without this, monthly and yearly base plans of one subscription are
     * indistinguishable — both arrive as the same [productId] — and an app is
     * left inferring the current plan from its own records, which drift.
     */
    val basePlanId: String? = null,

    /**
     * Whether the store currently considers this entitlement live.
     *
     * `false` covers expiry, account hold, pause and refund alike — which of
     * those it is comes from [status]. Inactive entitlements only reach you via
     * [CustomerInfo.allEntitlements]; [CustomerInfo.activeEntitlements] filters
     * them out.
     */
    val isActive: Boolean = true,

    /**
     * Which store is billing this entitlement. An Android build regularly sees
     * [Store.APP_STORE] here — the user subscribed on iOS and signed in on this
     * device. Access carries across; plan changes do not.
     */
    val store: Store = Store.UNKNOWN,

    /** Pricing phase the subscription is currently in. */
    val periodType: PeriodType = PeriodType.NORMAL,

    /** When auto-renew was detected as off (unix seconds), or null while it is on. */
    val unsubscribeDetectedAtSeconds: Long? = null,

    /**
     * When a billing problem was first detected (unix seconds), or null when
     * there is none. Set throughout both the grace period and account hold, so
     * it does not by itself distinguish the two — [status] does.
     */
    val billingIssueDetectedAtSeconds: Long? = null,

    /**
     * When the grace period for the current billing failure runs out (unix
     * seconds). Once past, an unrecovered subscription moves to
     * [SubscriptionStatus.ON_HOLD].
     */
    val gracePeriodExpiresAtSeconds: Long? = null,

    /**
     * When a refund was detected (unix seconds), or null. A refund is final:
     * access ends at once rather than running out the paid period.
     */
    val refundedAtSeconds: Long? = null,

    /**
     * When a paused subscription resumes automatically (unix seconds). Google
     * Play only, and only set while the subscription is actually paused — it
     * is what [SubscriptionStatus.PAUSED] is detected from, and what the
     * "resumes on {date}" message renders.
     */
    val autoResumeAtSeconds: Long? = null,

    /**
     * Store-side transaction id (Play purchase token / App Store transaction
     * id) behind this entitlement, or null when the provider does not expose
     * one. Compare it against what the store currently reports to detect a
     * store-account switch.
     */
    val storeTransactionId: String? = null,

    /** How the user came to hold this entitlement. */
    val ownershipType: OwnershipType = OwnershipType.UNKNOWN,

    /** `true` for sandbox / test purchases. Never grant paid access on these in production. */
    val isSandbox: Boolean = false,
) {
    /**
     * The id to hand back to `purchase` / `changeSubscription`, and to match
     * against a [Product.id]: `"productId:basePlanId"` when the store reports a
     * base plan, otherwise just [productId].
     */
    val id: String
        get() = basePlanId?.takeIf { it.isNotEmpty() }?.let { "$productId:$it" } ?: productId

    /**
     * Lifecycle state, derived from the store's own signals.
     *
     * Order matters. A refund overrides everything. A pause is checked next
     * because the store only reports [autoResumeAtSeconds] while a
     * subscription is actually paused, and that signal is trustworthy whether
     * or not the entitlement still reads as live. The remaining live states
     * split on whether a billing issue is outstanding; the remaining lapsed
     * ones split on why, with a plain expiry as the fallback.
     */
    val status: SubscriptionStatus
        get() = when {
            refundedAtSeconds != null -> SubscriptionStatus.REFUNDED
            autoResumeAtSeconds != null -> SubscriptionStatus.PAUSED
            isActive && (isInGracePeriod || billingIssueDetectedAtSeconds != null) ->
                SubscriptionStatus.IN_GRACE_PERIOD
            isActive && !willRenew -> SubscriptionStatus.CANCELLED
            isActive -> SubscriptionStatus.ACTIVE
            billingIssueDetectedAtSeconds != null -> SubscriptionStatus.ON_HOLD
            else -> SubscriptionStatus.EXPIRED
        }

    /**
     * Whether this entitlement should unlock paid features right now. Shorthand
     * for `status.grantsAccess`; gate UI on this rather than on [isActive],
     * which says nothing about *why* an entitlement lapsed.
     */
    val grantsAccess: Boolean
        get() = status.grantsAccess
}
