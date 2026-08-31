package com.vdharmani.subscription

import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.Store
import com.vdharmani.subscription.model.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Case 6 state table. Every row of it, plus the orderings that are easy to
 * get wrong: a refund outranks everything, a pause is trusted even when the
 * entitlement still reads as live, and a cancelled-but-unexpired subscription
 * keeps access.
 */
class EntitlementStatusTest {

    @Test
    fun `renewing subscription is active`() {
        assertEquals(SubscriptionStatus.ACTIVE, entitlement().status)
        assertTrue(entitlement().grantsAccess)
    }

    @Test
    fun `cancelled but unexpired keeps access`() {
        val cancelled = entitlement(willRenew = false, unsubscribeDetectedAtSeconds = NOW)

        assertEquals(SubscriptionStatus.CANCELLED, cancelled.status)
        assertTrue("auto-renew off is not the same as expired", cancelled.grantsAccess)
    }

    @Test
    fun `billing issue while still active is a grace period`() {
        val grace = entitlement(
            isInGracePeriod = true,
            billingIssueDetectedAtSeconds = NOW,
            gracePeriodExpiresAtSeconds = NOW + 3600,
        )

        assertEquals(SubscriptionStatus.IN_GRACE_PERIOD, grace.status)
        assertTrue("grace period must retain benefits", grace.grantsAccess)
    }

    @Test
    fun `billing issue after the entitlement lapses is account hold`() {
        val hold = entitlement(
            isActive = false,
            willRenew = false,
            billingIssueDetectedAtSeconds = NOW,
            gracePeriodExpiresAtSeconds = NOW - 3600,
        )

        assertEquals(SubscriptionStatus.ON_HOLD, hold.status)
        assertFalse(hold.grantsAccess)
        assertTrue("hold is recoverable without a new purchase", hold.status.isRecoverable)
    }

    @Test
    fun `auto-resume date means paused even while the entitlement reads active`() {
        // The store only reports an auto-resume date while a subscription is
        // actually paused, so it must win over a stale active flag.
        val paused = entitlement(isActive = true, autoResumeAtSeconds = NOW + 86_400)

        assertEquals(SubscriptionStatus.PAUSED, paused.status)
        assertFalse(paused.grantsAccess)
    }

    @Test
    fun `refund outranks every other signal`() {
        val refunded = entitlement(
            isActive = true,
            billingIssueDetectedAtSeconds = NOW,
            autoResumeAtSeconds = NOW + 86_400,
            refundedAtSeconds = NOW,
        )

        assertEquals(SubscriptionStatus.REFUNDED, refunded.status)
        assertFalse("a refund ends access at once", refunded.grantsAccess)
    }

    @Test
    fun `lapsed with no other signal is a plain expiry`() {
        val expired = entitlement(isActive = false, willRenew = false)

        assertEquals(SubscriptionStatus.EXPIRED, expired.status)
        assertFalse(expired.grantsAccess)
    }

    @Test
    fun `id pairs the product with its base plan`() {
        assertEquals("premium:yearly", entitlement(basePlanId = "yearly").id)
        assertEquals("premium", entitlement(basePlanId = null).id)
        assertEquals("premium", entitlement(basePlanId = "").id)
    }

    private companion object {
        const val NOW = 1_700_000_000L

        fun entitlement(
            isActive: Boolean = true,
            willRenew: Boolean = true,
            isInGracePeriod: Boolean = false,
            basePlanId: String? = "monthly",
            unsubscribeDetectedAtSeconds: Long? = null,
            billingIssueDetectedAtSeconds: Long? = null,
            gracePeriodExpiresAtSeconds: Long? = null,
            refundedAtSeconds: Long? = null,
            autoResumeAtSeconds: Long? = null,
            store: Store = Store.PLAY_STORE,
        ) = Entitlement(
            identifier = "premium",
            productId = "premium",
            purchasedAtSeconds = NOW,
            expiresAtSeconds = NOW + 86_400,
            willRenew = willRenew,
            isInGracePeriod = isInGracePeriod,
            basePlanId = basePlanId,
            isActive = isActive,
            store = store,
            unsubscribeDetectedAtSeconds = unsubscribeDetectedAtSeconds,
            billingIssueDetectedAtSeconds = billingIssueDetectedAtSeconds,
            gracePeriodExpiresAtSeconds = gracePeriodExpiresAtSeconds,
            refundedAtSeconds = refundedAtSeconds,
            autoResumeAtSeconds = autoResumeAtSeconds,
        )
    }
}
