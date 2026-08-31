package com.vdharmani.subscription

import com.vdharmani.subscription.internal.entitlementForProduct
import com.vdharmani.subscription.internal.planChangeEligibility
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.PlanChangeEligibility
import com.vdharmani.subscription.model.Store
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases 5 and 7 — when a plan switch must be refused before the purchase sheet
 * opens, and, just as importantly, when it must not be.
 */
class PlanChangeGuardTest {

    @Test
    fun `same store and account allows the switch`() = runTest {
        val provider = FakeBillingProvider(Result.success(customerInfo(entitlement())))

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(PlanChangeEligibility.Allowed, result)
    }

    @Test
    fun `entitlement billed by the app store is blocked as cross-platform`() = runTest {
        val provider = FakeBillingProvider(
            Result.success(customerInfo(entitlement(store = Store.APP_STORE))),
        )

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        val blocked = result as PlanChangeEligibility.Blocked
        assertEquals(PlanChangeEligibility.Reason.CROSS_PLATFORM, blocked.reason)
        assertEquals(Store.APP_STORE, blocked.store)
    }

    @Test
    fun `a different store account is blocked as a mismatch`() = runTest {
        val provider = FakeBillingProvider(
            customerInfo = Result.success(customerInfo(entitlement())),
            ownedByCurrentAccount = Result.success(false),
        )

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(
            PlanChangeEligibility.Reason.STORE_ACCOUNT_MISMATCH,
            (result as PlanChangeEligibility.Blocked).reason,
        )
    }

    @Test
    fun `unknown ownership does not block`() = runTest {
        // null means "this provider cannot tell", which is the default. Reading
        // it as a mismatch would break every provider that omits the hook.
        val provider = FakeBillingProvider(
            customerInfo = Result.success(customerInfo(entitlement())),
            ownedByCurrentAccount = Result.success(null),
        )

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(PlanChangeEligibility.Allowed, result)
    }

    @Test
    fun `unknown store does not block`() = runTest {
        val provider = FakeBillingProvider(
            Result.success(customerInfo(entitlement(store = Store.UNKNOWN))),
        )

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(PlanChangeEligibility.Allowed, result)
    }

    @Test
    fun `a suspended subscription cannot be switched`() = runTest {
        val onHold = entitlement().copy(
            isActive = false,
            willRenew = false,
            billingIssueDetectedAtSeconds = 1L,
        )
        val provider = FakeBillingProvider(Result.success(customerInfo(onHold)))

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(
            PlanChangeEligibility.Reason.SUBSCRIPTION_NOT_ACTIVE,
            (result as PlanChangeEligibility.Blocked).reason,
        )
    }

    @Test
    fun `no subscription at all is blocked`() = runTest {
        val provider = FakeBillingProvider(Result.success(customerInfo()))

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(
            PlanChangeEligibility.Reason.NO_ACTIVE_SUBSCRIPTION,
            (result as PlanChangeEligibility.Blocked).reason,
        )
    }

    @Test
    fun `a failed customer-info lookup fails open`() = runTest {
        // Refusing every upgrade whenever the network blips would be a worse
        // bug than the one the guard exists to prevent.
        val provider = FakeBillingProvider(Result.failure(BillingNetworkException()))

        val result = provider.planChangeEligibility { it.entitlementForProduct("premium") }

        assertEquals(PlanChangeEligibility.Allowed, result)
    }

    @Test
    fun `entitlement lookup ignores the base-plan suffix`() = runTest {
        val info = customerInfo(entitlement(basePlanId = "monthly"))

        assertTrue(info.entitlementForProduct("premium:yearly") != null)
        assertTrue(info.entitlementForProduct("premium") != null)
        assertTrue(info.entitlementForProduct("other") == null)
    }

    private fun customerInfo(vararg entitlements: Entitlement) = CustomerInfo(
        appUserId = "user",
        activeEntitlements = entitlements.filter { it.isActive },
        nonConsumableProductIds = emptySet(),
        allEntitlements = entitlements.toList(),
    )

    private fun entitlement(
        store: Store = Store.PLAY_STORE,
        basePlanId: String? = "monthly",
    ) = Entitlement(
        identifier = "premium",
        productId = "premium",
        purchasedAtSeconds = 1L,
        expiresAtSeconds = 2L,
        willRenew = true,
        isInGracePeriod = false,
        basePlanId = basePlanId,
        isActive = true,
        store = store,
    )
}
