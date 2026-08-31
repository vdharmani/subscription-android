package com.vdharmani.subscription

import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.Store
import com.vdharmani.subscription.model.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Case 3's warning trigger, and the access/presence distinction cases 4 and 6 turn on. */
class CustomerInfoTest {

    @Test
    fun `a suspended entitlement is reachable even though it is not active`() {
        val onHold = entitlement(isActive = false, billingIssueDetectedAtSeconds = 1L)
        val info = customerInfo(onHold)

        assertFalse("hold must not be presented as an active entitlement", info.hasEntitlement("premium"))
        assertFalse(info.hasAccess("premium"))
        assertEquals(SubscriptionStatus.ON_HOLD, info.statusOf("premium"))
    }

    @Test
    fun `an unknown entitlement has no status`() {
        assertNull(customerInfo().statusOf("premium"))
        assertFalse(customerInfo().hasAccess("premium"))
    }

    @Test
    fun `deletion warning fires only while something still renews`() {
        assertTrue(customerInfo(entitlement()).hasRenewingSubscription)
    }

    @Test
    fun `an already-cancelled subscription needs no billing warning`() {
        // It still grants access, but nothing further will be charged, so
        // warning that billing continues would be a lie.
        val cancelled = entitlement(willRenew = false)

        assertTrue(customerInfo(cancelled).hasAccess("premium"))
        assertFalse(customerInfo(cancelled).hasRenewingSubscription)
    }

    @Test
    fun `a lapsed subscription needs no billing warning`() {
        val expired = entitlement(isActive = false, willRenew = false)

        assertFalse(customerInfo(expired).hasRenewingSubscription)
    }

    @Test
    fun `allEntitlements defaults to the active ones for older providers`() {
        val info = CustomerInfo(
            appUserId = "user",
            activeEntitlements = listOf(entitlement()),
            nonConsumableProductIds = emptySet(),
        )

        assertEquals(1, info.allEntitlements.size)
    }

    private fun customerInfo(vararg entitlements: Entitlement) = CustomerInfo(
        appUserId = "user",
        activeEntitlements = entitlements.filter { it.isActive },
        nonConsumableProductIds = emptySet(),
        allEntitlements = entitlements.toList(),
    )

    private fun entitlement(
        isActive: Boolean = true,
        willRenew: Boolean = true,
        billingIssueDetectedAtSeconds: Long? = null,
    ) = Entitlement(
        identifier = "premium",
        productId = "premium",
        purchasedAtSeconds = 1L,
        expiresAtSeconds = 2L,
        willRenew = willRenew,
        isInGracePeriod = false,
        basePlanId = "monthly",
        isActive = isActive,
        store = Store.PLAY_STORE,
        billingIssueDetectedAtSeconds = billingIssueDetectedAtSeconds,
    )
}
