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
 *
 * **Custom implementations:** map your SDK's error codes onto the
 * [BillingException] subclasses (`BillingNetworkException`,
 * `PaymentDeclinedException`, etc.) so consumers can dispatch on type.
 * Use [UnknownBillingException] as a fallback. Wrapping in plain
 * `Exception`/`IllegalStateException` works but defeats the whole point of
 * the typed hierarchy — callers can't tell "network down" from "payment
 * declined" and end up with one generic error UI.
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

/**
 * Base type for billing failures surfaced via `Result.failure`.
 *
 * Lets callers dispatch on a specific reason (network, payment declined,
 * product not available, …) without unwrapping provider-specific exception
 * types. Custom [BillingProvider] implementations should map their SDK's
 * errors onto the subclasses below; [UnknownBillingException] is the
 * fallback when no more-specific mapping is appropriate.
 *
 * **Heads up:** [PurchaseCancelledException] is a [BillingException]. A naive
 * `if (e is BillingException) showError(e)` will mistakenly show an error
 * dialog when the user simply dismissed the purchase sheet. Always check
 * cancellation first:
 *
 * ```kotlin
 * result.onFailure { e ->
 *     when (e) {
 *         is PurchaseCancelledException -> {}            // normal action
 *         is BillingException -> showError(e)             // real failure
 *         else -> showError(e)                            // unexpected
 *     }
 * }
 * ```
 */
sealed class BillingException(message: String?, cause: Throwable? = null) :
    Exception(message, cause)

/** The user dismissed the purchase sheet. Treat as a normal action, not an error. */
class PurchaseCancelledException :
    BillingException("User cancelled the purchase flow")

/** No connectivity, request timed out, or the store endpoint was unreachable. */
class BillingNetworkException(message: String? = null, cause: Throwable? = null) :
    BillingException(message ?: "Network error talking to the store", cause)

/** The store rejected payment (user/family/device payment method declined). */
class PaymentDeclinedException(message: String? = null, cause: Throwable? = null) :
    BillingException(message ?: "Payment was not accepted by the store", cause)

/** The requested product id is not configured / not available in the store. */
class ProductUnavailableException(message: String? = null, cause: Throwable? = null) :
    BillingException(message ?: "Product is not available for purchase", cause)

/** The user already owns this product / has an active receipt for it. */
class AlreadyOwnedException(message: String? = null, cause: Throwable? = null) :
    BillingException(message ?: "Product is already owned", cause)

/** Store-side problem (backend down, unexpected response). Retrying may help. */
class StoreProblemException(message: String? = null, cause: Throwable? = null) :
    BillingException(message ?: "Store reported a problem", cause)

/** Catch-all for failures that don't map to a more specific subclass. */
class UnknownBillingException(message: String? = null, cause: Throwable? = null) :
    BillingException(message ?: "Unknown billing failure", cause)

/** Thrown via `Result.failure` when no [BillingProvider] is registered. */
class ProviderNotInitializedException :
    IllegalStateException(
        "SubscriptionManager.initialize(...) has not been called. " +
            "Call it from your Application.onCreate before using SubscriptionClient or ComposeSubscription.",
    )
