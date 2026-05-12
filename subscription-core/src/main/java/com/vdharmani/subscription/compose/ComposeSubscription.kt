package com.vdharmani.subscription.compose

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vdharmani.subscription.BillingProvider
import com.vdharmani.subscription.SubscriptionClient
import com.vdharmani.subscription.SubscriptionManager
import com.vdharmani.subscription.internal.playStoreInstallerCheck
import com.vdharmani.subscription.model.ProductType

/**
 * Compose-native subscription surface.
 *
 * Returns a [SubscriptionComposeManager] backed by the registered
 * [BillingProvider] (or one you pass explicitly). Customer-info changes from
 * the underlying provider are surfaced as a `State<CustomerInfo?>` you can
 * read directly inside composables.
 *
 * ```kotlin
 * val sub = ComposeSubscription()
 * val info by sub.customerInfo
 * val isPremium = info?.hasEntitlement("premium") == true
 *
 * Button(onClick = {
 *     scope.launch { sub.purchase("monthly", ProductType.SUBS) }
 * }) { Text(if (isPremium) "Manage" else "Subscribe") }
 * ```
 *
 * @param provider The [BillingProvider] to use. Defaults to the one registered
 *   via [SubscriptionManager.initialize].
 * @param config Optional safety knobs (currently just `requirePlayStoreInstaller`).
 */
@Suppress("ComposableNaming")
@Composable
fun ComposeSubscription(
    provider: BillingProvider = SubscriptionManager.provider(),
    config: SubscriptionClient.Config = SubscriptionClient.Config(),
): SubscriptionComposeManager {
    val context = LocalContext.current

    // Remember the flow so recompositions don't tear down and re-subscribe to
    // the provider's customer-info stream on every frame.
    val customerInfoFlow = remember(provider) { provider.observeCustomerInfo() }
    val customerInfoState = customerInfoFlow.collectAsStateWithLifecycle(initialValue = null)

    return remember(provider, config) {
        SubscriptionComposeManager(
            onPurchase = { productId, productType ->
                val activity = context.findActivity()
                    ?: return@SubscriptionComposeManager Result.failure(
                        IllegalStateException("ComposeSubscription must be hosted in an Activity to launch purchases."),
                    )
                if (config.requirePlayStoreInstaller) {
                    playStoreInstallerCheck(context)?.let {
                        return@SubscriptionComposeManager Result.failure(it)
                    }
                }
                provider.purchase(activity, productId, productType)
            },
            onRestore = { provider.restore() },
            onCustomerInfo = { provider.customerInfo() },
            onIdentify = { provider.identify(it) },
            onLogout = { provider.logout() },
            customerInfo = customerInfoState,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
