package com.vdharmani.subscription.model

/**
 * Snapshot of the current user's purchase + entitlement state.
 *
 * Returned from `customerInfo()` / `identify()` / `logout()`, and emitted by
 * `observeCustomerInfo()` whenever the provider notices a change
 * (renewal, billing failure recovery, restore, identity switch).
 */
data class CustomerInfo(
    /** Provider-assigned (anonymous) or app-assigned (after `identify`) user id. */
    val appUserId: String,

    /**
     * Entitlements currently active for [appUserId]. Empty list = user is not subscribed
     * and has no non-expiring entitlements.
     */
    val activeEntitlements: List<Entitlement>,

    /**
     * Product ids of non-consumable [ProductType.INAPP] purchases this user owns.
     * Use this when your "passes" or "lifetime unlocks" aren't modelled as
     * entitlements in the provider dashboard.
     */
    val nonConsumableProductIds: Set<String>,
) {
    /** Convenience: true if [identifier] is in [activeEntitlements]. */
    fun hasEntitlement(identifier: String): Boolean =
        activeEntitlements.any { it.identifier == identifier }
}
