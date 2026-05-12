package com.vdharmani.subscription

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.vdharmani.subscription.internal.playStoreInstallerCheck
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
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
         * builds whose installer-package-name cannot be read (typically
         * sideloaded APKs). Matches the safety check from the reference
         * implementation. Default: `false` — opt-in.
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

    suspend fun restore(): Result<CustomerInfo> = provider.restore()
    suspend fun customerInfo(): Result<CustomerInfo> = provider.customerInfo()
    suspend fun identify(appUserId: String): Result<CustomerInfo> = provider.identify(appUserId)
    suspend fun logout(): Result<CustomerInfo> = provider.logout()

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
}
