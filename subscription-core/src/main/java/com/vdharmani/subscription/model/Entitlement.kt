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

    /** The product id that granted this entitlement most recently. */
    val productId: String,

    /** When the granting purchase was made. */
    val purchasedAtSeconds: Long,

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
)
