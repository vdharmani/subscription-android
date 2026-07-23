package com.vdharmani.subscription.compose

import androidx.compose.runtime.State
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Product
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import com.vdharmani.subscription.model.ReplacementMode
import com.vdharmani.subscription.model.SubscriberAttributes

/**
 * Compose-side counterpart to `SubscriptionClient`. Construct via
 * [ComposeSubscription]; you should not instantiate this class directly.
 *
 * All method names mirror `SubscriptionClient` so a developer who knows one
 * surface can use the other.
 */
class SubscriptionComposeManager internal constructor(
    private val onPurchase: suspend (productId: String, productType: ProductType) -> Result<Receipt>,
    private val onChangeSubscription: suspend (
        productId: String,
        oldProductId: String,
        replacementMode: ReplacementMode,
    ) -> Result<Receipt>,
    private val onProducts: suspend (
        productIds: List<String>,
        productType: ProductType,
    ) -> Result<List<Product>>,
    private val onRestore: suspend () -> Result<CustomerInfo>,
    private val onCustomerInfo: suspend () -> Result<CustomerInfo>,
    private val onIdentify: suspend (appUserId: String) -> Result<CustomerInfo>,
    private val onSetAttributes: suspend (attributes: SubscriberAttributes) -> Result<Unit>,
    private val onLogout: suspend () -> Result<CustomerInfo>,
    /**
     * Lifecycle-aware snapshot of customer info, kept up to date by the
     * provider's observe-stream. Read it directly in your composables.
     */
    val customerInfo: State<CustomerInfo?>,
) {
    suspend fun purchase(productId: String, productType: ProductType): Result<Receipt> =
        onPurchase(productId, productType)

    /** Upgrade/downgrade an active subscription. See `BillingProvider.changeSubscription`. */
    suspend fun changeSubscription(
        productId: String,
        oldProductId: String,
        replacementMode: ReplacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
    ): Result<Receipt> = onChangeSubscription(productId, oldProductId, replacementMode)

    /** Store-localised products for the paywall. See `BillingProvider.products`. */
    suspend fun products(
        productIds: List<String>,
        productType: ProductType = ProductType.SUBS,
    ): Result<List<Product>> = onProducts(productIds, productType)

    suspend fun restore(): Result<CustomerInfo> = onRestore()
    suspend fun customerInfo(): Result<CustomerInfo> = onCustomerInfo()
    suspend fun identify(appUserId: String): Result<CustomerInfo> = onIdentify(appUserId)

    /** Attach subscriber metadata to the current identity — call after [identify]. */
    suspend fun setAttributes(attributes: SubscriberAttributes): Result<Unit> = onSetAttributes(attributes)

    suspend fun logout(): Result<CustomerInfo> = onLogout()
}
