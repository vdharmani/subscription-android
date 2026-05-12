package com.vdharmani.subscription

import android.app.Activity
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import kotlinx.coroutines.flow.Flow

/**
 * Plug-in interface for the underlying billing SDK.
 *
 * `subscription-core` knows nothing about RevenueCat (or Play Billing, or any
 * other SDK). To enable purchases, register a [BillingProvider] implementation
 * via [SubscriptionManager.initialize] from your `Application.onCreate`. The
 * `subscription-revenuecat` artifact ships a RevenueCat-backed implementation
 * (`RevenueCatProvider`).
 *
 * All methods are `suspend` and return [Result]; failures land in
 * `Result.failure` with a typed exception, never thrown across the API boundary.
 */
interface BillingProvider {

    /**
     * Launch the platform purchase flow for [productId] of [productType].
     *
     * The implementation is responsible for fetching the underlying product,
     * launching the Play purchase sheet against [activity], and resolving once
     * the user completes or cancels the flow. Cancellation produces
     * `Result.failure(PurchaseCancelledException)`; treat it as a normal user
     * action, not an error to surface.
     */
    suspend fun purchase(
        activity: Activity,
        productId: String,
        productType: ProductType,
    ): Result<Receipt>

    /**
     * Re-sync the user's purchase history with the Play Store. Useful for the
     * App Store / Play Store "Restore Purchases" button.
     *
     * Returns the freshly-synced [CustomerInfo] — that's what providers
     * actually compute. Active entitlements and owned non-consumables are
     * available on the returned snapshot, and any subscribers to
     * [observeCustomerInfo] will see the same update.
     */
    suspend fun restore(): Result<CustomerInfo>

    /** Fetch the current [CustomerInfo] snapshot. */
    suspend fun customerInfo(): Result<CustomerInfo>

    /**
     * Switch the active billing identity to [appUserId]. Use after the user
     * signs in to your app, so their purchases follow them across devices.
     * Returns the [CustomerInfo] for the new identity.
     */
    suspend fun identify(appUserId: String): Result<CustomerInfo>

    /**
     * Drop the current identity and create a fresh anonymous one. Use on sign-out.
     * Returns the [CustomerInfo] for the new anonymous identity (which will
     * have no entitlements unless previously transferred).
     */
    suspend fun logout(): Result<CustomerInfo>

    /**
     * Hot flow of [CustomerInfo] updates. Emits a new value whenever the
     * provider notices a change — renewal, billing failure, billing recovery,
     * a restore, or an identity switch. Collect from a lifecycle-scoped
     * coroutine on the View side, or via `collectAsStateWithLifecycle()` in
     * Compose.
     */
    fun observeCustomerInfo(): Flow<CustomerInfo>
}

/** Thrown via `Result.failure` when the user cancels the purchase sheet. */
class PurchaseCancelledException : Exception("User cancelled the purchase flow")

/** Thrown via `Result.failure` when no [BillingProvider] is registered. */
class ProviderNotInitializedException :
    IllegalStateException(
        "SubscriptionManager.initialize(...) has not been called. " +
            "Call it from your Application.onCreate before using SubscriptionClient or ComposeSubscription.",
    )
