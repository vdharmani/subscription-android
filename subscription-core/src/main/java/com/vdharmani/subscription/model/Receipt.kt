package com.vdharmani.subscription.model

/**
 * The result of a successful purchase, shaped for a server-side
 * verify/grant-entitlement call.
 *
 * The library does **not** post this to your server — that's the consumer's
 * responsibility. Build whatever request body your backend wants from these
 * fields. Designed to be provider-agnostic, so swapping the underlying SDK
 * (RevenueCat ↔ Play Billing) leaves your network code untouched.
 */
data class Receipt(
    /** Provider-assigned (anonymous) or app-assigned (after `identify`) user id. */
    val appUserId: String,

    /** Play Store SKU. */
    val productId: String,

    /** Which kind of product this was. */
    val productType: ProductType,

    /** Play order id (used for receipt validation). May be empty if the SDK didn't supply one. */
    val transactionId: String,

    /** When the purchase completed, in unix seconds. */
    val purchasedAtSeconds: Long,

    /** Price paid by the user, in major currency units (e.g. 9.99, not 9990000 micros). */
    val price: Double,

    /** ISO-4217 currency code (e.g. "USD", "INR"). */
    val currency: String,
)
