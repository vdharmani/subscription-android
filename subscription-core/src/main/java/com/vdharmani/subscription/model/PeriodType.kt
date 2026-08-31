package com.vdharmani.subscription.model

/**
 * Which pricing phase the subscription is currently in.
 *
 * Relevant to paywall copy ("your trial ends…") and to trial-eligibility
 * decisions: eligibility is scoped to the **store** account, not the app
 * account, so a returning user may already have burned the trial this field
 * describes.
 */
enum class PeriodType {
    /** Standard paid period. */
    NORMAL,

    /** Free trial granted by the store. */
    TRIAL,

    /** Introductory (discounted) pricing period. */
    INTRO,

    /** Prepaid plan — paid up front, does not auto-renew. */
    PREPAID,
}
