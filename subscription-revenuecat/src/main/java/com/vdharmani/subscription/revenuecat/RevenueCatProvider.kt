package com.vdharmani.subscription.revenuecat

import android.app.Activity
import android.content.Context
import com.revenuecat.purchases.CustomerInfo as RcCustomerInfo
import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.ProductType as RcProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import com.vdharmani.subscription.AlreadyOwnedException
import com.vdharmani.subscription.BillingNetworkException
import com.vdharmani.subscription.BillingProvider
import com.vdharmani.subscription.PaymentDeclinedException
import com.vdharmani.subscription.ProductUnavailableException
import com.vdharmani.subscription.PurchaseCancelledException
import com.vdharmani.subscription.StoreProblemException
import com.vdharmani.subscription.UnknownBillingException
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * [BillingProvider] implementation backed by [RevenueCat](https://www.revenuecat.com/).
 *
 * Register from your `Application.onCreate`:
 *
 * ```kotlin
 * SubscriptionManager.initialize(
 *     RevenueCatProvider(this, BuildConfig.REVENUECAT_KEY),
 * )
 * ```
 *
 * The constructor calls [Purchases.configure] once per process — repeated
 * instantiation is a no-op for the underlying SDK.
 *
 * @param context Any Context; only used to call `Purchases.configure`.
 * @param apiKey Your RevenueCat **Android** public API key.
 * @param debugLogs Enable RevenueCat's verbose logging. Defaults to `false`.
 */
class RevenueCatProvider(
    context: Context,
    apiKey: String,
    debugLogs: Boolean = false,
) : BillingProvider {

    /**
     * Scope used for the one-time customer-info snapshot fetch. The provider
     * is a process-lifetime singleton (created in Application.onCreate), so
     * the scope's lifetime is the app's. A `SupervisorJob` prevents an early
     * fetch failure from cancelling future work.
     */
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // replay=1 so late subscribers get the most-recent snapshot immediately.
    // extraBufferCapacity=1 + DROP_OLDEST so a slow collector can't cause
    // tryEmit() to return false and silently swallow a customer-info update.
    private val customerInfoSink = MutableSharedFlow<CustomerInfo>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val customerInfoFlow: Flow<CustomerInfo> = customerInfoSink.asSharedFlow().distinctUntilChanged()

    init {
        synchronized(Purchases::class.java) {
            if (!Purchases.isConfigured) {
                if (debugLogs) Purchases.logLevel = LogLevel.DEBUG
                Purchases.configure(
                    PurchasesConfiguration.Builder(context.applicationContext, apiKey).build(),
                )
            }
        }

        // Register the global listener once. RevenueCat exposes a single
        // listener slot; any subsequent `Purchases.sharedInstance.updatedCustomerInfoListener =`
        // call would replace ours, so collectors fan out through our SharedFlow
        // instead of subscribing to RevenueCat directly.
        Purchases.sharedInstance.updatedCustomerInfoListener =
            UpdatedCustomerInfoListener { rcInfo ->
                customerInfoSink.tryEmit(rcInfo.toCustomerInfo())
            }

        // Push an initial snapshot so late subscribers see something
        // immediately (the SharedFlow's replay=1 cache holds it).
        providerScope.launch {
            runCatching {
                val snapshot = Purchases.sharedInstance.awaitCustomerInfo()
                customerInfoSink.tryEmit(snapshot.toCustomerInfo())
            }
        }
    }

    // -- purchase ---------------------------------------------------------

    override suspend fun purchase(
        activity: Activity,
        productId: String,
        productType: ProductType,
    ): Result<Receipt> = runCatching {
        val rcType = productType.toRevenueCat()
        val products: List<StoreProduct> = Purchases.sharedInstance.awaitGetProducts(
            productIds = listOf(productId),
            type = rcType,
        )
        val product = products.firstOrNull { it.id == productId || it.id.startsWith("$productId:") }
            ?: error("Product not found in Play Console: $productId (type=$productType)")

        val params = PurchaseParams.Builder(activity, product).build()
        val outcome = try {
            Purchases.sharedInstance.awaitPurchase(params)
        } catch (e: PurchasesException) {
            throw e.toBillingException()
        }

        product.toReceipt(
            appUserId = Purchases.sharedInstance.appUserID,
            productType = productType,
            transaction = outcome.storeTransaction,
        )
    }

    // -- restore / customer info -----------------------------------------

    override suspend fun restore(): Result<CustomerInfo> = runCatching {
        Purchases.sharedInstance.awaitRestore().toCustomerInfo()
    }

    override suspend fun customerInfo(): Result<CustomerInfo> = runCatching {
        Purchases.sharedInstance.awaitCustomerInfo().toCustomerInfo()
    }

    // -- identity ---------------------------------------------------------

    override suspend fun identify(appUserId: String): Result<CustomerInfo> = runCatching {
        Purchases.sharedInstance.awaitLogIn(appUserId).customerInfo.toCustomerInfo()
    }

    override suspend fun logout(): Result<CustomerInfo> = runCatching {
        Purchases.sharedInstance.awaitLogOut().toCustomerInfo()
    }

    // -- live updates -----------------------------------------------------

    override fun observeCustomerInfo(): Flow<CustomerInfo> = customerInfoFlow

    // -- mapping ----------------------------------------------------------

    private fun ProductType.toRevenueCat(): RcProductType = when (this) {
        ProductType.INAPP -> RcProductType.INAPP
        ProductType.SUBS -> RcProductType.SUBS
    }

    private fun StoreProduct.toReceipt(
        appUserId: String,
        productType: ProductType,
        transaction: StoreTransaction,
    ): Receipt = Receipt(
        appUserId = appUserId,
        productId = id,
        productType = productType,
        transactionId = transaction.orderId.orEmpty(),
        purchasedAtSeconds = transaction.purchaseTime / 1000L,
        price = price.amountMicros / 1_000_000.0,
        priceAmountMicros = price.amountMicros,
        currency = price.currencyCode,
    )

    private fun RcCustomerInfo.toCustomerInfo(): CustomerInfo = CustomerInfo(
        appUserId = originalAppUserId,
        activeEntitlements = entitlements.active.values.map { it.toEntitlement() },
        nonConsumableProductIds = nonSubscriptionTransactions
            .map { it.productIdentifier }
            .toSet(),
    )

    private fun EntitlementInfo.toEntitlement(): Entitlement = Entitlement(
        identifier = identifier,
        productId = productIdentifier,
        purchasedAtSeconds = latestPurchaseDate?.let { it.time / 1000L },
        expiresAtSeconds = expirationDate?.let { it.time / 1000L },
        willRenew = willRenew,
        isInGracePeriod = billingIssueDetectedAt != null,
    )

    private fun PurchasesException.toBillingException(): Throwable = when (code) {
        PurchasesErrorCode.PurchaseCancelledError ->
            PurchaseCancelledException()

        PurchasesErrorCode.NetworkError ->
            BillingNetworkException(error.message, this)

        PurchasesErrorCode.PurchaseNotAllowedError,
        PurchasesErrorCode.PurchaseInvalidError,
        PurchasesErrorCode.PaymentPendingError ->
            PaymentDeclinedException(error.message, this)

        PurchasesErrorCode.ProductNotAvailableForPurchaseError ->
            ProductUnavailableException(error.message, this)

        PurchasesErrorCode.ProductAlreadyPurchasedError,
        PurchasesErrorCode.ReceiptAlreadyInUseError ->
            AlreadyOwnedException(error.message, this)

        PurchasesErrorCode.StoreProblemError,
        PurchasesErrorCode.UnexpectedBackendResponseError,
        PurchasesErrorCode.UnknownBackendError ->
            StoreProblemException(error.message, this)

        else ->
            UnknownBillingException(error.message, this)
    }
}
