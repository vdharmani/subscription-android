package com.vdharmani.subscription.model

/**
 * How the store settles the remaining time on the subscription being replaced
 * when the user switches plans (see `BillingProvider.changeSubscription`).
 *
 * The names mirror Google Play's replacement modes; a provider on another store
 * maps them onto its closest equivalent.
 *
 * **Not every mode is legal for every switch.** When both plans are base plans
 * of the *same* subscription product — the common "one product, monthly +
 * yearly base plan" setup — Play accepts only [CHARGE_FULL_PRICE] and
 * [WITHOUT_PRORATION]; anything else fails the purchase and shows the user an
 * error. Prefer [forPlanSwitch] over picking a mode by hand.
 */
enum class ReplacementMode {
    /**
     * Charge the prorated price difference for the rest of the current period
     * and switch immediately. The billing date stays put.
     *
     * Play accepts this **only when the price per unit of time increases**, and
     * never for two base plans of one subscription product. A monthly → annual
     * move normally *lowers* the price per month (that's the discount you're
     * advertising), so this is the wrong mode for the usual upgrade — use
     * [CHARGE_FULL_PRICE].
     */
    CHARGE_PRORATED_PRICE,

    /**
     * Switch immediately and credit the unused time by pushing the next billing
     * date out. Nothing is charged today.
     *
     * Play rejects this when switching between base plans of one subscription
     * product.
     */
    WITH_TIME_PRORATION,

    /**
     * Switch immediately, keeping the current billing date; nothing is charged
     * today and the new price applies from the next renewal.
     *
     * Legal for every Play transition, base plans of one product included. The
     * safe choice for a **downgrade** (annual → monthly): the user keeps the
     * time they already paid for and only pays the lower price afterwards.
     */
    WITHOUT_PRORATION,

    /**
     * Switch immediately and charge the full new price today. The unused time
     * on the old plan is carried over as credit that extends the new period.
     *
     * Legal for every Play transition — base plans of one product included, and
     * the only mode allowed when moving to a prepaid plan. The safe choice for
     * an **upgrade** (monthly → annual).
     */
    CHARGE_FULL_PRICE,

    /**
     * Keep the current plan until the period ends, then start the new one.
     *
     * Play rejects this when switching between base plans of one subscription
     * product; use [WITHOUT_PRORATION] there, which the user experiences the
     * same way — nothing to pay until the paid-for period runs out.
     */
    DEFERRED,
    ;

    /** Whether Play allows this mode for a switch inside a single subscription product. */
    val isSameSubscriptionSafe: Boolean
        get() = this == CHARGE_FULL_PRICE || this == WITHOUT_PRORATION

    /**
     * The closest mode Play accepts inside a single subscription product: modes
     * that settle up front collapse to [CHARGE_FULL_PRICE], modes that defer
     * the cost collapse to [WITHOUT_PRORATION].
     */
    fun sameSubscriptionEquivalent(): ReplacementMode = when (this) {
        CHARGE_PRORATED_PRICE, WITH_TIME_PRORATION, CHARGE_FULL_PRICE -> CHARGE_FULL_PRICE
        WITHOUT_PRORATION, DEFERRED -> WITHOUT_PRORATION
    }

    companion object {
        /**
         * Mode for a plan switch, picked so the store accepts it.
         *
         * @param isUpgrade `true` when the user is moving to the plan you sell
         *   as the better one (typically monthly → annual). Upgrades charge
         *   today so access changes immediately.
         * @param sameSubscription `true` (the default) when both plans are base
         *   plans of one subscription product, which is what Play's stricter
         *   rules apply to. Pass `false` only for two separate products, where
         *   a downgrade can be deferred to the end of the paid period.
         */
        fun forPlanSwitch(isUpgrade: Boolean, sameSubscription: Boolean = true): ReplacementMode = when {
            isUpgrade -> CHARGE_FULL_PRICE
            sameSubscription -> WITHOUT_PRORATION
            else -> DEFERRED
        }
    }
}
