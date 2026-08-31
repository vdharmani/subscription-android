package com.vdharmani.subscription.revenuecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Case 5's decision rules.
 *
 * `false` is the only answer that blocks a plan change, so most of what
 * follows is about the cases that must *not* produce it. The failure this
 * guards against is a wrong `false` reaching every user at once and blocking
 * every upgrade in the app.
 */
class StoreAccountMatchTest {

    @Test
    fun `owned by this account when the product matches`() {
        val result = ownership(purchases = listOf(purchase(products = listOf("premium"))))

        assertEquals(true, result)
    }

    @Test
    fun `owning nothing is the one case that blocks`() {
        val result = ownership(purchases = emptyList())

        assertEquals(false, result)
    }

    @Test
    fun `a different product on this account also blocks`() {
        val result = ownership(purchases = listOf(purchase(products = listOf("other_sub"))))

        assertEquals(false, result)
    }

    @Test
    fun `base plan suffixes are ignored on either side`() {
        // RevenueCat reports the bare id in productIdentifier, but the
        // compound form appears in activeSubscriptions and webhooks. Matching
        // must survive either arriving.
        assertEquals(
            true,
            ownership(
                entitlementProductId = "premium:monthly",
                purchases = listOf(purchase(products = listOf("premium"))),
            ),
        )
        assertEquals(
            true,
            ownership(
                entitlementProductId = "premium",
                purchases = listOf(purchase(products = listOf("premium:yearly"))),
            ),
        )
    }

    @Test
    fun `a pending purchase never blocks`() {
        // Mid-purchase is not evidence of somebody else's account.
        val result = ownership(
            purchases = listOf(purchase(products = listOf("premium"), isPurchased = false)),
        )

        assertNull(result)
    }

    @Test
    fun `the GPA order id matches when the product does not`() {
        // RevenueCat's store_transaction_id for Google Play is the GPA order
        // id, so this is the form that actually arrives.
        val result = ownership(
            entitlementTransactionId = "GPA.3309-9122-6177-45730",
            purchases = listOf(
                purchase(products = listOf("renamed_sku"), orderId = "GPA.3309-9122-6177-45730"),
            ),
        )

        assertEquals(true, result)
    }

    @Test
    fun `a purchase token match is accepted too`() {
        val result = ownership(
            entitlementTransactionId = "token-abc",
            purchases = listOf(purchase(products = listOf("renamed"), purchaseToken = "token-abc")),
        )

        assertEquals(true, result)
    }

    @Test
    fun `a blank transaction id is not treated as a match`() {
        val result = ownership(
            entitlementTransactionId = "",
            purchases = listOf(purchase(products = listOf("other"), orderId = "")),
        )

        assertEquals(false, result)
    }

    @Test
    fun `the right purchase wins when the account owns several`() {
        val result = ownership(
            purchases = listOf(
                purchase(products = listOf("other_a")),
                purchase(products = listOf("premium")),
                purchase(products = listOf("other_b")),
            ),
        )

        assertEquals(true, result)
    }

    @Test
    fun `a purchased entry outranks a pending one for the same product`() {
        val result = ownership(
            purchases = listOf(
                purchase(products = listOf("premium"), isPurchased = false),
                purchase(products = listOf("premium")),
            ),
        )

        assertEquals(true, result)
    }

    @Test
    fun `only a genuine absence ever returns false`() {
        // The invariant the whole guard rests on, stated as a test.
        val blocking = listOf(
            ownership(purchases = emptyList()),
            ownership(purchases = listOf(purchase(products = listOf("unrelated")))),
        )
        val notBlocking = listOf(
            ownership(purchases = listOf(purchase(products = listOf("premium")))),
            ownership(purchases = listOf(purchase(products = listOf("premium"), isPurchased = false))),
        )

        assertTrue(blocking.all { it == false })
        assertFalse(notBlocking.any { it == false })
    }

    private fun ownership(
        entitlementProductId: String = "premium",
        entitlementTransactionId: String? = null,
        purchases: List<StorePurchase>,
    ): Boolean? = StoreAccountMatch.ownership(
        entitlementProductId = entitlementProductId,
        entitlementTransactionId = entitlementTransactionId,
        purchases = purchases,
    )

    private fun purchase(
        products: List<String>,
        orderId: String? = "GPA.0000-0000-0000-00000",
        purchaseToken: String? = "token-default",
        isPurchased: Boolean = true,
    ) = StorePurchase(
        products = products,
        orderId = orderId,
        purchaseToken = purchaseToken,
        isPurchased = isPurchased,
    )
}
