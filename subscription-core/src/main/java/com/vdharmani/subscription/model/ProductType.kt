package com.vdharmani.subscription.model

/**
 * Whether a product is a one-shot in-app purchase or an auto-renewing subscription.
 *
 * The Play Store represents these as separate product types and the underlying
 * billing SDK (RevenueCat or Play Billing) requires us to distinguish at
 * purchase time. Pass the right value to [com.vdharmani.subscription.SubscriptionClient.purchase]
 * or [com.vdharmani.subscription.compose.SubscriptionComposeManager.purchase].
 */
enum class ProductType {
    /** One-time purchase. Consumable or non-consumable. */
    INAPP,

    /** Auto-renewing subscription. */
    SUBS,
}
