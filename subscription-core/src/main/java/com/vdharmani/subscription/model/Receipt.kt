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

    /**
     * Play order id (used for receipt validation). `null` when the underlying
     * SDK didn't supply one — distinct from an empty string, which used to be
     * the silent fallback and could cause server-side verify endpoints to
     * accept bogus receipts.
     */
    val transactionId: String?,

    /** When the purchase completed, in unix seconds. */
    val purchasedAtSeconds: Long,

    /** Price paid by the user, in major currency units (e.g. 9.99, not 9990000 micros). */
    val price: Double,

    /**
     * Price paid in micros (1 unit = 1,000,000). Prefer this for server-side
     * verification and any accounting — it preserves exact precision across
     * all currencies, including zero-decimal ones like JPY. [price] is a
     * `Double` rounded down for display convenience.
     */
    val priceAmountMicros: Long,

    /** ISO-4217 currency code (e.g. "USD", "INR"). */
    val currency: String,
)
