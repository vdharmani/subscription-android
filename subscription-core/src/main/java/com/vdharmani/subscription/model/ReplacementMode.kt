package com.vdharmani.subscription.model

/**
 * How the store settles the remaining time on the subscription being replaced
 * when the user switches plans (see `BillingProvider.changeSubscription`).
 *
 * The names mirror Google Play's replacement modes; a provider on another store
 * maps them onto its closest equivalent.
 */
enum class ReplacementMode {
    /**
     * Charge the prorated price for the rest of the current period and switch
     * immediately. The billing date stays put. The usual choice for an
     * **upgrade** (monthly → annual).
     */
    CHARGE_PRORATED_PRICE,

    /**
     * Switch immediately and credit the unused time by pushing the next billing
     * date out. Nothing is charged today.
     */
    WITH_TIME_PRORATION,

    /** Switch immediately, no credit and no charge for the unused time. */
    WITHOUT_PRORATION,

    /** Switch immediately and charge the full new price today, restarting the period. */
    CHARGE_FULL_PRICE,

    /**
     * Keep the current plan until the period ends, then start the new one. The
     * usual choice for a **downgrade** (annual → monthly) — the user keeps what
     * they already paid for.
     */
    DEFERRED,
}
