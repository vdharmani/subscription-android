package com.vdharmani.subscription.model

/**
 * Which store billed the purchase behind an [Entitlement].
 *
 * Entitlements follow the **app account**, so an Android build routinely sees
 * entitlements that Apple — not Google — is billing (the user subscribed on
 * their iPhone and then signed in here). Access still applies; what does *not*
 * carry across is the ability to change or cancel the plan, because only the
 * originating store can do that. [managedHere] is the flag that distinguishes
 * the two.
 */
enum class Store {
    PLAY_STORE,
    AMAZON,
    GALAXY,
    APP_STORE,
    MAC_APP_STORE,
    STRIPE,
    PADDLE,
    RC_BILLING,
    EXTERNAL,
    PROMOTIONAL,
    TEST_STORE,
    UNKNOWN,
    ;

    /** `true` for stores billed through an Android device. */
    val isAndroid: Boolean
        get() = this == PLAY_STORE || this == AMAZON || this == GALAXY

    /** `true` for stores billed through an Apple device. */
    val isApple: Boolean
        get() = this == APP_STORE || this == MAC_APP_STORE

    /**
     * `true` for web/third-party billing (Stripe, Paddle, RevenueCat Billing,
     * or an external processor). Access carries into the app, but the plan is
     * managed on the website — never offer an in-app purchase on top, which
     * would double-bill.
     */
    val isWeb: Boolean
        get() = this == STRIPE || this == PADDLE || this == RC_BILLING || this == EXTERNAL

    /**
     * Whether an app running on **this** device can drive a plan change against
     * this store. Only true for the store the registered
     * [com.vdharmani.subscription.BillingProvider] actually talks to; see
     * [com.vdharmani.subscription.BillingProvider.nativeStore].
     *
     * When this is false the plan is read-only here — show the user where it
     * *can* be managed instead of offering an upgrade button that would start a
     * second, parallel subscription.
     */
    fun managedHere(nativeStore: Store): Boolean = this == nativeStore
}
