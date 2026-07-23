package com.vdharmani.subscription

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.vdharmani.subscription.internal.playStoreInstallerCheck
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Product
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import com.vdharmani.subscription.model.ReplacementMode
import com.vdharmani.subscription.model.SubscriberAttributes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * View-side wrapper around a [BillingProvider].
 *
 * Construct from an Activity or a Fragment. The wrapper takes care of:
 *   - running suspending operations against the host's `lifecycleScope`,
 *   - offering callback variants for codebases that don't use coroutines yet,
 *   - the optional Play-Store-installer safety check from the reference impl.
 *
 * For Compose, use [com.vdharmani.subscription.compose.ComposeSubscription] instead.
 */
class SubscriptionClient private constructor(
    private val activity: Activity,
    private val lifecycleOwner: LifecycleOwner,
    private val provider: BillingProvider,
    private val config: Config,
) {

    /** Construct for an Activity. */
    constructor(
        activity: ComponentActivity,
        provider: BillingProvider = SubscriptionManager.provider(),
        config: Config = Config(),
    ) : this(
        activity = activity,
        lifecycleOwner = activity,
        provider = provider,
        config = config,
    )

    /** Construct for a Fragment. */
    constructor(
        fragment: Fragment,
        provider: BillingProvider = SubscriptionManager.provider(),
        config: Config = Config(),
    ) : this(
        activity = fragment.requireActivity(),
        lifecycleOwner = fragment,
        provider = provider,
        config = config,
    )

    data class Config(
        /**
         * When `true`, [purchase] is blocked on debuggable builds and on
         * builds whose installer is anything other than the Play Store
         * (`com.android.vending`) — so sideloads, F-Droid installs, OEM
         * preloads, and instrumentation tests are all rejected. Matches the
         * safety check from the reference implementation. Default: `false` —
         * opt-in.
         */
        val requirePlayStoreInstaller: Boolean = false,
    )

    // -- suspend API ------------------------------------------------------

    /**
     * Launch the purchase flow for [productId] of [productType].
     *
     * Cancellations land in `Result.failure(PurchaseCancelledException)` —
     * treat them as normal user actions, not errors.
     */
    suspend fun purchase(productId: String, productType: ProductType): Result<Receipt> {
        if (config.requirePlayStoreInstaller) {
            playStoreInstallerCheck(activity)?.let { return Result.failure(it) }
        }
        return provider.purchase(activity, productId, productType)
    }

    /**
     * Switch the active subscription from [oldProductId] to [productId] — see
     * [BillingProvider.changeSubscription]. Same installer check as [purchase].
     */
    suspend fun changeSubscription(
        productId: String,
        oldProductId: String,
        replacementMode: ReplacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
    ): Result<Receipt> {
        if (config.requirePlayStoreInstaller) {
            playStoreInstallerCheck(activity)?.let { return Result.failure(it) }
        }
        return provider.changeSubscription(activity, productId, oldProductId, replacementMode)
    }

    /**
     * Store-localised products for the paywall. See [BillingProvider.products].
     */
    suspend fun products(
        productIds: List<String>,
        productType: ProductType = ProductType.SUBS,
    ): Result<List<Product>> = provider.products(productIds, productType)

    suspend fun restore(): Result<CustomerInfo> = provider.restore()
    suspend fun customerInfo(): Result<CustomerInfo> = provider.customerInfo()
    suspend fun identify(appUserId: String): Result<CustomerInfo> = provider.identify(appUserId)
    suspend fun logout(): Result<CustomerInfo> = provider.logout()

    /**
     * Attach subscriber metadata (purchase email, display name, custom keys) to
     * the current identity — call after [identify]. See
     * [BillingProvider.setAttributes].
     */
    suspend fun setAttributes(attributes: SubscriberAttributes): Result<Unit> =
        provider.setAttributes(attributes)

    /** Hot flow of customer-info updates. See [BillingProvider.observeCustomerInfo]. */
    fun observeCustomerInfo(): Flow<CustomerInfo> = provider.observeCustomerInfo()

    // -- callback API -----------------------------------------------------

    fun purchase(
        productId: String,
        productType: ProductType,
        onResult: (Result<Receipt>) -> Unit,
    ) {
        lifecycleOwner.lifecycleScope.launch { onResult(purchase(productId, productType)) }
    }

    fun changeSubscription(
        productId: String,
        oldProductId: String,
        replacementMode: ReplacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
        onResult: (Result<Receipt>) -> Unit,
    ) {
        lifecycleOwner.lifecycleScope.launch {
            onResult(changeSubscription(productId, oldProductId, replacementMode))
        }
    }

    fun products(
        productIds: List<String>,
        productType: ProductType = ProductType.SUBS,
        onResult: (Result<List<Product>>) -> Unit,
    ) {
        lifecycleOwner.lifecycleScope.launch { onResult(products(productIds, productType)) }
    }

    fun restore(onResult: (Result<CustomerInfo>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(restore()) }
    }

    fun customerInfo(onResult: (Result<CustomerInfo>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(customerInfo()) }
    }

    fun identify(appUserId: String, onResult: (Result<CustomerInfo>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(identify(appUserId)) }
    }

    fun logout(onResult: (Result<CustomerInfo>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(logout()) }
    }

    fun setAttributes(attributes: SubscriberAttributes, onResult: (Result<Unit>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(setAttributes(attributes)) }
    }
}
