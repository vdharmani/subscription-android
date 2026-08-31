package com.vdharmani.subscription.internal

import com.vdharmani.subscription.BillingProvider
import com.vdharmani.subscription.SubscriptionAlreadyLinkedException
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.PlanChangeEligibility
import com.vdharmani.subscription.model.RestoreOutcome
import com.vdharmani.subscription.model.Store

/**
 * Decide whether a plan change may start, shared by the View and Compose
 * surfaces so both enforce identical rules.
 *
 * [selectEntitlement] picks which entitlement the decision is about — by
 * identifier from the Manage Subscription screen, or by the product being
 * replaced from `changeSubscription`.
 */
internal suspend fun BillingProvider.planChangeEligibility(
    selectEntitlement: (CustomerInfo) -> Entitlement?,
): PlanChangeEligibility {
    // Fail open on a failed lookup. A plan change is worth blocking when we
    // know it would misfire, but a flaky network is not evidence of that, and
    // refusing upgrades whenever customer info can't be fetched would be a
    // worse bug than the one this guard prevents.
    val info = customerInfo().getOrNull() ?: return PlanChangeEligibility.Allowed

    val entitlement = selectEntitlement(info)
        ?: return PlanChangeEligibility.Blocked(
            reason = PlanChangeEligibility.Reason.NO_ACTIVE_SUBSCRIPTION,
            store = nativeStore,
        )

    if (!entitlement.grantsAccess) {
        return PlanChangeEligibility.Blocked(
            reason = PlanChangeEligibility.Reason.SUBSCRIPTION_NOT_ACTIVE,
            store = entitlement.store,
            entitlement = entitlement,
        )
    }

    // UNKNOWN means the provider didn't report a store, not that the store is
    // foreign — blocking on it would break every provider that omits the field.
    if (entitlement.store != Store.UNKNOWN && entitlement.store != nativeStore) {
        return PlanChangeEligibility.Blocked(
            reason = PlanChangeEligibility.Reason.CROSS_PLATFORM,
            store = entitlement.store,
            entitlement = entitlement,
        )
    }

    // Only an explicit `false` blocks. `null` is "this provider can't tell",
    // which is the default, and must not be read as a mismatch.
    if (ownedByCurrentStoreAccount(entitlement).getOrNull() == false) {
        return PlanChangeEligibility.Blocked(
            reason = PlanChangeEligibility.Reason.STORE_ACCOUNT_MISMATCH,
            store = entitlement.store,
            entitlement = entitlement,
        )
    }

    return PlanChangeEligibility.Allowed
}

/**
 * Locate the entitlement backing [productId], ignoring any base-plan suffix —
 * a monthly → yearly switch inside one subscription keeps the same product id
 * and only the base plan differs.
 */
internal fun CustomerInfo.entitlementForProduct(productId: String): Entitlement? {
    val bareProductId = productId.substringBefore(':')
    return allEntitlements.firstOrNull { it.id == productId }
        ?: allEntitlements.firstOrNull { it.productId == bareProductId }
}

/**
 * Classify a finished restore, turning the Case 1 conflict into its own
 * outcome instead of an anonymous failure.
 */
internal fun Result<CustomerInfo>.toRestoreOutcome(): RestoreOutcome = fold(
    onSuccess = { info ->
        // "Nothing to restore" means the store returned no purchases at all.
        // A lapsed or suspended entitlement is still something — the caller
        // needs to show why it lapsed, not "No purchases found".
        if (info.allEntitlements.isEmpty() && info.nonConsumableProductIds.isEmpty()) {
            RestoreOutcome.NothingToRestore(info)
        } else {
            RestoreOutcome.Restored(info)
        }
    },
    onFailure = { error ->
        if (error is SubscriptionAlreadyLinkedException) {
            RestoreOutcome.LinkedToAnotherAccount(error)
        } else {
            RestoreOutcome.Failed(error)
        }
    },
)
