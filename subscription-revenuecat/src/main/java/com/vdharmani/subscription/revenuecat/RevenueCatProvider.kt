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
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import com.vdharmani.subscription.BillingProvider
import com.vdharmani.subscription.PurchaseCancelledException
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [BillingProvider] implementation backed by [RevenueCat](https://www.revenuecat.com/).
 *
 * Register from your `Application.onCreate`:
 *
 * ```kotlin
 * SubscriptionManager.initialize(
 *     context = this,
 *     provider = RevenueCatProvider(this, BuildConfig.REVENUECAT_KEY),
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

    init {
        synchronized(Purchases::class.java) {
            if (!Purchases.isConfigured) {
                if (debugLogs) Purchases.logLevel = LogLevel.DEBUG
                Purchases.configure(
                    PurchasesConfiguration.Builder(context.applicationContext, apiKey).build(),
                )
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
            if (e.code == PurchasesErrorCode.PurchaseCancelledError) {
                throw PurchaseCancelledException()
            }
            throw IllegalStateException(e.error.message, e)
        }

        product.toReceipt(
            appUserId = Purchases.sharedInstance.appUserID,
            productType = productType,
            transaction = outcome.storeTransaction,
        )
    }

    // -- restore / customer info -----------------------------------------

    override suspend fun restore(): Result<List<Receipt>> = runCatching {
        val info = Purchases.sharedInstance.awaitRestore()
        info.toAllReceipts()
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

    override fun observeCustomerInfo(): Flow<CustomerInfo> = callbackFlow {
        val listener = UpdatedCustomerInfoListener { info ->
            trySend(info.toCustomerInfo())
        }
        Purchases.sharedInstance.updatedCustomerInfoListener = listener

        // Emit the current cached snapshot so collectors see something
        // immediately even before the next update fires.
        runCatching {
            val snapshot = Purchases.sharedInstance.awaitCustomerInfo()
            trySend(snapshot.toCustomerInfo())
        }

        awaitClose {
            if (Purchases.sharedInstance.updatedCustomerInfoListener === listener) {
                Purchases.sharedInstance.updatedCustomerInfoListener = null
            }
        }
    }.distinctUntilChanged()

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
        purchasedAtSeconds = (latestPurchaseDate?.time ?: 0L) / 1000L,
        expiresAtSeconds = expirationDate?.let { it.time / 1000L },
        willRenew = willRenew,
        isInGracePeriod = billingIssueDetectedAt != null,
    )

    /**
     * `restorePurchases` returns a [RcCustomerInfo] — we don't get an itemised
     * receipt list back from RevenueCat. To keep the API honest, we surface
     * one [Receipt] per active entitlement and per non-consumable purchase,
     * filled in with what RevenueCat actually knows. Server-side, you should
     * match by `productId` + `transactionId`.
     */
    private fun RcCustomerInfo.toAllReceipts(): List<Receipt> {
        val appUserId = originalAppUserId
        // Active entitlements always come from a subscription product.
        val fromEntitlements = entitlements.active.values.map { e ->
            Receipt(
                appUserId = appUserId,
                productId = e.productIdentifier,
                productType = ProductType.SUBS,
                transactionId = e.store.name,                        // RC doesn't expose the order id here
                purchasedAtSeconds = (e.latestPurchaseDate?.time ?: 0L) / 1000L,
                price = 0.0,                                          // not available from EntitlementInfo
                currency = "",
            )
        }
        val fromInapp = nonSubscriptionTransactions.map { tx ->
            Receipt(
                appUserId = appUserId,
                productId = tx.productIdentifier,
                productType = ProductType.INAPP,
                transactionId = tx.transactionIdentifier,
                purchasedAtSeconds = tx.purchaseDate.time / 1000L,
                price = 0.0,
                currency = "",
            )
        }
        return (fromEntitlements + fromInapp).distinctBy { it.transactionId + it.productId }
    }
}

