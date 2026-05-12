package com.vdharmani.subscription.compose

import androidx.compose.runtime.State
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt

/**
 * Compose-side counterpart to `SubscriptionClient`. Construct via
 * [ComposeSubscription]; you should not instantiate this class directly.
 *
 * All method names mirror `SubscriptionClient` so a developer who knows one
 * surface can use the other.
 */
class SubscriptionComposeManager internal constructor(
    private val onPurchase: suspend (productId: String, productType: ProductType) -> Result<Receipt>,
    private val onRestore: suspend () -> Result<List<Receipt>>,
    private val onCustomerInfo: suspend () -> Result<CustomerInfo>,
    private val onIdentify: suspend (appUserId: String) -> Result<CustomerInfo>,
    private val onLogout: suspend () -> Result<CustomerInfo>,
    /**
     * Lifecycle-aware snapshot of customer info, kept up to date by the
     * provider's observe-stream. Read it directly in your composables.
     */
    val customerInfo: State<CustomerInfo?>,
) {
    suspend fun purchase(productId: String, productType: ProductType): Result<Receipt> =
        onPurchase(productId, productType)

    suspend fun restore(): Result<List<Receipt>> = onRestore()
    suspend fun customerInfo(): Result<CustomerInfo> = onCustomerInfo()
    suspend fun identify(appUserId: String): Result<CustomerInfo> = onIdentify(appUserId)
    suspend fun logout(): Result<CustomerInfo> = onLogout()
}
