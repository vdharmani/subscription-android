package com.vdharmani.subscription

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
            installerCheckFailure(activity)?.let { return Result.failure(it) }
        }
        return provider.purchase(activity, productId, productType)
    }

    suspend fun restore(): Result<List<Receipt>> = provider.restore()
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
    ) = lifecycleOwner.lifecycleScope.launch {
        onResult(purchase(productId, productType))
    }.let { Unit }

    fun restore(onResult: (Result<List<Receipt>>) -> Unit) =
        lifecycleOwner.lifecycleScope.launch { onResult(restore()) }.let { Unit }

    fun customerInfo(onResult: (Result<CustomerInfo>) -> Unit) =
        lifecycleOwner.lifecycleScope.launch { onResult(customerInfo()) }.let { Unit }

    fun identify(appUserId: String, onResult: (Result<CustomerInfo>) -> Unit) =
        lifecycleOwner.lifecycleScope.launch { onResult(identify(appUserId)) }.let { Unit }

    fun logout(onResult: (Result<CustomerInfo>) -> Unit) =
        lifecycleOwner.lifecycleScope.launch { onResult(logout()) }.let { Unit }

    // -- internals --------------------------------------------------------

    private fun installerCheckFailure(context: Context): IllegalStateException? {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val installer = installerPackageName(context)
        return if (debuggable || installer == null) {
            IllegalStateException(
                "Purchases are blocked: app must be installed from the Play Store " +
                    "(debuggable=$debuggable, installer=$installer). Disable this check " +
                    "with SubscriptionClient.Config(requirePlayStoreInstaller = false) for development.",
            )
        } else null
    }

    private fun installerPackageName(context: Context): String? = try {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    } catch (_: Exception) {
        null
    }
}
