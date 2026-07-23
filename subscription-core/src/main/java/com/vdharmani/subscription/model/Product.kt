package com.vdharmani.subscription.model

/**
 * A purchasable product as the **store** describes it for **this** user — the
 * price is already converted, formatted, and localised for their region and
 * currency, so a paywall built from these never shows the wrong number abroad
 * or goes stale when you re-price in the console.
 *
 * Fetch with `BillingProvider.products(...)`.
 */
data class Product(
    /**
     * The id to hand back to `purchase`. For a Google subscription with base
     * plans this is `"productId:basePlanId"`; for everything else it equals
     * [productId].
     */
    val id: String,

    /** Store product id, without any base-plan suffix. */
    val productId: String,

    /** Base plan this entry describes, or null when the product has none. */
    val basePlanId: String?,

    val type: ProductType,

    /** Store-provided display title. */
    val title: String,

    /** Store-provided description; empty when the console has none. */
    val description: String,

    /** Recurring price, ready to render (e.g. `"₹1,600.00"`). */
    val price: Price,

    /**
     * How often [price] recurs, or null for one-shot [ProductType.INAPP]
     * products. Drives "/mo" vs "/yr" labels without parsing strings.
     */
    val billingPeriod: Period?,

    /**
     * Free trial the store will apply on purchase, or null when there is none.
     * This is the store's own trial (an offer on the base plan) — unrelated to
     * a trial your backend grants.
     */
    val freeTrialPeriod: Period? = null,
) {
    /** Convenience for "per month" maths on a yearly plan: price ÷ months. */
    val pricePerMonthMicros: Long?
        get() = billingPeriod?.inMonths?.takeIf { it > 0 }?.let { (price.amountMicros / it).toLong() }
}

/**
 * A localised price. [formatted] is what you display — it already carries the
 * user's currency symbol and grouping. [amountMicros] and [currencyCode] are
 * for maths and analytics, never for display.
 */
data class Price(
    val formatted: String,
    val amountMicros: Long,
    val currencyCode: String,
)

/** A billing duration, e.g. one month or one year. */
data class Period(
    val value: Int,
    val unit: Unit,
    /** Raw ISO-8601 form from the store, e.g. `"P1M"`. */
    val iso8601: String,
) {
    enum class Unit { DAY, WEEK, MONTH, YEAR, UNKNOWN }

    /** Length in months (0.25 for a week, 12 for a year) — handy for /mo maths. */
    val inMonths: Double
        get() = when (unit) {
            Unit.DAY -> value / 30.0
            Unit.WEEK -> value / 4.0
            Unit.MONTH -> value.toDouble()
            Unit.YEAR -> value * 12.0
            Unit.UNKNOWN -> 0.0
        }
}
