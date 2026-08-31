package com.vdharmani.subscription.model

/**
 * Whether the app may start a plan change right now.
 *
 * A plan switch has to be blocked *before* the purchase flow opens, because
 * the failure modes are bad in both directions: on Google Play an upgrade
 * against a stale purchase token fails loudly with a developer error, and on
 * the App Store it fails *silently* — StoreKit only applies an upgrade when
 * the same Apple ID owns the old subscription, so with a different one active
 * it quietly becomes a brand-new full-price purchase and the user ends up
 * paying two live subscriptions.
 *
 * Obtain one from `SubscriptionClient.planChangeEligibility` and use it to
 * decide whether the Manage Subscription screen shows an upgrade button or an
 * explanatory line.
 */
sealed interface PlanChangeEligibility {

    /** The plan can be changed from this device, on this store account. */
    data object Allowed : PlanChangeEligibility

    /**
     * The plan cannot be changed here. [reason] says why, and
     * `SubscriptionMessages.planChangeBlocked` turns it into the line to show.
     */
    data class Blocked(
        val reason: Reason,
        /** Store that is billing the subscription, for the message copy. */
        val store: Store,
        /** The entitlement the decision was made about, when there was one. */
        val entitlement: Entitlement? = null,
    ) : PlanChangeEligibility

    enum class Reason {
        /**
         * Another store is billing the subscription — bought on iOS, viewed on
         * Android or vice versa. Access carries across, management does not.
         * Never offer a second purchase here: that double-bills.
         */
        CROSS_PLATFORM,

        /**
         * The right store, but a different store account. The user switched
         * Google accounts after subscribing, so the purchase token backing the
         * entitlement is not one this account owns.
         */
        STORE_ACCOUNT_MISMATCH,

        /** Nothing to change from — the user holds no live subscription. */
        NO_ACTIVE_SUBSCRIPTION,

        /**
         * The subscription is suspended (account hold, paused, refunded). Fix
         * or resubscribe first; a switch would be rejected.
         */
        SUBSCRIPTION_NOT_ACTIVE,
    }
}
