package com.vdharmani.subscription

import com.vdharmani.subscription.internal.toRestoreOutcome
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.RestoreOutcome
import com.vdharmani.subscription.model.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore decision table — in particular keeping "the store had nothing"
 * apart from "this subscription belongs to another app account", which are the
 * two outcomes plain `restore()` collapses together.
 */
class RestoreOutcomeTest {

    @Test
    fun `an empty store account is nothing to restore`() {
        val outcome = Result.success(customerInfo()).toRestoreOutcome()

        assertTrue(outcome is RestoreOutcome.NothingToRestore)
    }

    @Test
    fun `an active entitlement restores`() {
        val outcome = Result.success(customerInfo(entitlement())).toRestoreOutcome()

        assertTrue(outcome is RestoreOutcome.Restored)
    }

    @Test
    fun `a lapsed entitlement still counts as something restored`() {
        // "No purchases found" would be wrong here: the user has a subscription
        // in hold and needs to be told why it lapsed, not that it never existed.
        val onHold = entitlement().copy(isActive = false, billingIssueDetectedAtSeconds = 1L)

        val outcome = Result.success(customerInfo(onHold)).toRestoreOutcome()

        assertTrue(outcome is RestoreOutcome.Restored)
    }

    @Test
    fun `a non-consumable alone counts as something restored`() {
        val info = customerInfo().copy(nonConsumableProductIds = setOf("lifetime"))

        assertTrue(Result.success(info).toRestoreOutcome() is RestoreOutcome.Restored)
    }

    @Test
    fun `a linked-elsewhere conflict gets its own outcome`() {
        val error = SubscriptionAlreadyLinkedException()

        val outcome = Result.failure<CustomerInfo>(error).toRestoreOutcome()

        assertEquals(error, (outcome as RestoreOutcome.LinkedToAnotherAccount).error)
    }

    @Test
    fun `other failures stay generic`() {
        val outcome = Result.failure<CustomerInfo>(BillingNetworkException()).toRestoreOutcome()

        assertTrue(outcome is RestoreOutcome.Failed)
    }

    @Test
    fun `the conflict still matches an existing already-owned branch`() {
        // Consumers written against 1.6 dispatch on AlreadyOwnedException. The
        // new type extends it so those branches keep firing; code that wants
        // the conflict copy has to test the specific type first.
        val error: Throwable = SubscriptionAlreadyLinkedException()

        val matched = when (error) {
            is SubscriptionAlreadyLinkedException -> "conflict"
            is AlreadyOwnedException -> "already owned"
            else -> "other"
        }

        assertEquals("conflict", matched)
        assertTrue(error is AlreadyOwnedException)
    }

    private fun customerInfo(vararg entitlements: Entitlement) = CustomerInfo(
        appUserId = "user",
        activeEntitlements = entitlements.filter { it.isActive },
        nonConsumableProductIds = emptySet(),
        allEntitlements = entitlements.toList(),
    )

    private fun entitlement() = Entitlement(
        identifier = "premium",
        productId = "premium",
        purchasedAtSeconds = 1L,
        expiresAtSeconds = 2L,
        willRenew = true,
        isInGracePeriod = false,
        basePlanId = "monthly",
        isActive = true,
        store = Store.PLAY_STORE,
    )
}
