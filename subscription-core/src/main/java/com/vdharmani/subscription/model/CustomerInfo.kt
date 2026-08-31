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
     *
     * A subscription in account hold, paused, expired or refunded is **not**
     * here — it is in [allEntitlements]. Reading only this list is what makes a
     * failed payment look identical to a user who never subscribed, so the app
     * offers "Subscribe" instead of "Fix your payment method".
     */
    val activeEntitlements: List<Entitlement>,

    /**
     * Product ids of non-consumable [ProductType.INAPP] purchases this user owns.
     * Use this when your "passes" or "lifetime unlocks" aren't modelled as
     * entitlements in the provider dashboard.
     */
    val nonConsumableProductIds: Set<String>,

    /**
     * Every entitlement known for this user, live or lapsed — the superset of
     * [activeEntitlements]. Drive "payment issue", "paused" and "refunded"
     * messaging from here, gating on [Entitlement.grantsAccess].
     *
     * Defaults to [activeEntitlements] so a provider written before this field
     * existed still compiles; such a provider simply never surfaces the
     * suspended states.
     */
    val allEntitlements: List<Entitlement> = activeEntitlements,

    /**
     * Deep link to the store's subscription-management screen for this user, as
     * the provider reports it, or null when it has none. Prefer it over a
     * hand-built URL — it points at the store that is actually billing, which
     * for a cross-platform user is not necessarily Google Play. Fall back to
     * `SubscriptionClient.openManageSubscription` when null.
     */
    val managementUrl: String? = null,
) {
    /**
     * Convenience: true if [identifier] is currently active.
     *
     * This is presence, not permission — an entitlement can be present and
     * still not grant access. Use [hasAccess] to gate features.
     */
    fun hasEntitlement(identifier: String): Boolean =
        activeEntitlements.any { it.identifier == identifier }

    /**
     * Whether [identifier] should unlock paid features right now, per its
     * [SubscriptionStatus]. This is the check to gate UI on.
     */
    fun hasAccess(identifier: String): Boolean =
        entitlement(identifier)?.grantsAccess == true

    /**
     * The entitlement recorded for [identifier], active or not, or null when
     * the user has never held it. Use it to render the state message for a
     * suspended subscription.
     */
    fun entitlement(identifier: String): Entitlement? =
        activeEntitlements.firstOrNull { it.identifier == identifier }
            ?: allEntitlements.firstOrNull { it.identifier == identifier }

    /** Lifecycle state of [identifier], or null when the user has never held it. */
    fun statusOf(identifier: String): SubscriptionStatus? = entitlement(identifier)?.status

    /**
     * Whether deleting the app account should warn that billing continues.
     *
     * True only while something is still set to auto-renew — an already
     * cancelled subscription bills nothing further, so the warning would be a
     * lie and the plain deletion confirmation is the right dialog. Deleting is
     * never blocked either way; the store subscription is not the app's to
     * cancel.
     */
    val hasRenewingSubscription: Boolean
        get() = allEntitlements.any { it.willRenew && it.grantsAccess }
}
