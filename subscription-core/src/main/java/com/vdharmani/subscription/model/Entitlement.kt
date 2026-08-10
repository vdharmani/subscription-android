package com.vdharmani.subscription.model

/**
 * A single active entitlement for the current user.
 *
 * Entitlements are the "permissions" your app grants based on a purchase
 * (e.g. `"premium"`, `"pro_features"`). They map to one or more product ids.
 * The set of active entitlements is what most apps actually use to gate UI;
 * the underlying [Receipt] is the verifiable record.
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
) {
    /**
     * The id to hand back to `purchase` / `changeSubscription`, and to match
     * against a [Product.id]: `"productId:basePlanId"` when the store reports a
     * base plan, otherwise just [productId].
     */
    val id: String
        get() = basePlanId?.takeIf { it.isNotEmpty() }?.let { "$productId:$it" } ?: productId
}
