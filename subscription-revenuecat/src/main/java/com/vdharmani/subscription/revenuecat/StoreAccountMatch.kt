package com.vdharmani.subscription.revenuecat

/**
 * A purchase as Google Play reports it, reduced to the fields the ownership
 * decision needs. Exists so the rules below can be exercised without a store,
 * a device, or a Play account — `Purchase` is final and cannot be constructed
 * in a test.
 */
internal data class StorePurchase(
    val products: List<String>,
    val orderId: String?,
    val purchaseToken: String?,
    /** `false` while Play still reports the purchase as pending. */
    val isPurchased: Boolean,
)

/**
 * Decides whether the signed-in Play account owns the purchase behind an
 * entitlement — the rules for Case 5, kept pure and separate from the
 * BillingClient plumbing.
 *
 * The asymmetry is the whole point. `true` and `null` are both safe answers;
 * `false` blocks a plan change, so it is only ever returned when the account
 * demonstrably owns nothing matching.
 */
internal object StoreAccountMatch {

    /**
     * `true` when the account owns it, `false` when it demonstrably does not,
     * `null` when the answer is not safe to give — a purchase Play still
     * reports as pending is mid-flight, and treating that as "someone else's"
     * would block a user in the middle of their own upgrade.
     */
    fun ownership(
        entitlementProductId: String,
        entitlementTransactionId: String?,
        purchases: List<StorePurchase>,
    ): Boolean? {
        val matching = purchases.filter {
            it.matches(entitlementProductId, entitlementTransactionId)
        }
        return when {
            matching.any { it.isPurchased } -> true
            matching.isNotEmpty() -> null
            else -> false
        }
    }

    private fun StorePurchase.matches(
        entitlementProductId: String,
        entitlementTransactionId: String?,
    ): Boolean {
        // Compared base id to base id. RevenueCat reports the bare
        // subscription id in productIdentifier and the base plan separately,
        // while Play reports bare ids in Purchase.products — but the compound
        // "subscriptionId:basePlanId" form appears elsewhere in RevenueCat
        // (activeSubscriptions, webhooks). Stripping both sides makes the
        // match correct whichever form arrives, instead of silently matching
        // nothing and blocking every plan change.
        val wanted = entitlementProductId.baseProductId()
        if (products.any { it.baseProductId() == wanted }) return true

        // Secondary signal only. For Google Play this is the GPA order id, so
        // it is compared against orderId; the token is checked too so a
        // provider that reports one instead still matches. Never the basis for
        // a negative — a mismatch here alone proves nothing.
        val transactionId = entitlementTransactionId?.takeIf { it.isNotBlank() } ?: return false
        return transactionId == orderId || transactionId == purchaseToken
    }

    private fun String.baseProductId(): String = substringBefore(':')
}
