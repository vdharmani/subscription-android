package com.vdharmani.subscription.revenuecat

import android.app.Activity
import android.content.Context
import android.util.Log
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
import com.revenuecat.purchases.models.GoogleStoreProduct
import com.revenuecat.purchases.models.Period as RcPeriod
import com.revenuecat.purchases.models.Price as RcPrice
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreReplacementMode
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
import com.vdharmani.subscription.model.Period
import com.vdharmani.subscription.model.Price
import com.vdharmani.subscription.model.Product
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import com.vdharmani.subscription.model.ReplacementMode
import com.vdharmani.subscription.model.SubscriberAttributes
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
 * @param appUserId If your user is already authenticated when this provider
 *   is constructed, pass their stable app user id here to skip the
 *   anonymous-then-identify round-trip on startup. `null` (default) leaves
 *   RC anonymous; call [identify] later once you know the user id.
 */
class RevenueCatProvider(
    context: Context,
    apiKey: String,
    debugLogs: Boolean = false,
    appUserId: String? = null,
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
                    PurchasesConfiguration.Builder(context.applicationContext, apiKey)
                        .apply { if (appUserId != null) appUserID(appUserId) }
                        .build(),
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

    // -- catalogue --------------------------------------------------------

    /**
     * Prices come back already localised by Play for the user's region, so the
     * caller renders [Price.formatted] as-is. Callers may pass ids either bare
     * or as `"productId:basePlanId"`; Play is queried with the bare ids, and a
     * subscription with several base plans yields one entry per plan.
     */
    override suspend fun products(
        productIds: List<String>,
        productType: ProductType,
    ): Result<List<Product>> = runCatching {
        val storeIds = productIds.map { it.substringBefore(BASE_PLAN_SEPARATOR) }.distinct()
        Purchases.sharedInstance.awaitGetProducts(productIds = storeIds, type = productType.toRevenueCat())
            .map { it.toProduct(productType) }
    }

    // -- purchase ---------------------------------------------------------

    override suspend fun purchase(
        activity: Activity,
        productId: String,
        productType: ProductType,
    ): Result<Receipt> = runCatching {
        buyProduct(activity, productId, productType) { it }
    }

    /**
     * Plan switch. Play needs the product being *replaced* plus a replacement
     * mode on the same purchase call — without them the store would start a
     * second, parallel subscription instead of moving the user across.
     *
     * Switching between base plans of one subscription is the stricter case:
     * Play only accepts `CHARGE_FULL_PRICE` and `WITHOUT_PRORATION` there and
     * fails the purchase outright for the rest, so an unusable mode is mapped
     * onto its closest legal equivalent rather than handed to the store.
     */
    override suspend fun changeSubscription(
        activity: Activity,
        productId: String,
        oldProductId: String,
        replacementMode: ReplacementMode,
    ): Result<Receipt> = runCatching {
        val oldStoreProductId = oldProductId.substringBefore(BASE_PLAN_SEPARATOR)
        val sameSubscription = oldStoreProductId == productId.substringBefore(BASE_PLAN_SEPARATOR)
        val effectiveMode = if (sameSubscription && !replacementMode.isSameSubscriptionSafe) {
            replacementMode.sameSubscriptionEquivalent().also { fallback ->
                Log.w(
                    TAG,
                    "Play rejects $replacementMode when switching base plans of $oldStoreProductId; " +
                        "using $fallback instead.",
                )
            }
        } else {
            replacementMode
        }
        buyProduct(activity, productId, ProductType.SUBS) { builder ->
            builder
                // Play identifies the purchase being replaced by subscription id
                // alone — it ignores any base-plan suffix — so a base-plan switch
                // inside one subscription passes the same id here.
                .oldProductId(oldStoreProductId)
                .replacementMode(effectiveMode.toRevenueCat())
        }
    }

    /**
     * Shared purchase path: resolve the store product, let [configure] add any
     * extra params (e.g. the plan-switch fields), run the flow, and map the
     * outcome to a [Receipt]. SDK errors are typed on the way out.
     */
    private suspend fun buyProduct(
        activity: Activity,
        productId: String,
        productType: ProductType,
        configure: (PurchaseParams.Builder) -> PurchaseParams.Builder,
    ): Receipt {
        val rcType = productType.toRevenueCat()
        // Play is queried by the bare subscription id; a "productId:basePlanId"
        // caller id selects which base plan of that subscription to buy.
        val storeProductId = productId.substringBefore(BASE_PLAN_SEPARATOR)
        val basePlanId = productId.substringAfter(BASE_PLAN_SEPARATOR, missingDelimiterValue = "")
        val products: List<StoreProduct> = Purchases.sharedInstance.awaitGetProducts(
            productIds = listOf(storeProductId),
            type = rcType,
        )
        val product = if (basePlanId.isEmpty()) {
            // No base plan asked for: exact id first, then the single base plan
            // of a one-plan subscription.
            products.firstOrNull { it.id == productId }
                ?: products.singleOrNull { it.id.startsWith("$productId$BASE_PLAN_SEPARATOR") }
                ?: products.firstOrNull { it.id.startsWith("$productId$BASE_PLAN_SEPARATOR") }
        } else {
            products.firstOrNull { it.id == productId }
        }
            ?: error("Product not found in Play Console: $productId (type=$productType)")

        val params = configure(PurchaseParams.Builder(activity, product)).build()
        val outcome = try {
            Purchases.sharedInstance.awaitPurchase(params)
        } catch (e: PurchasesException) {
            throw e.toBillingException()
        }

        return product.toReceipt(
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

    /**
     * RevenueCat's attribute setters are fire-and-forget: values are stored
     * locally against the current app user id and uploaded with the next sync,
     * so this returns as soon as they're accepted. Null fields are skipped
     * (leave untouched); a blank value is passed through, which is how
     * RevenueCat deletes an attribute.
     */
    override suspend fun setAttributes(attributes: SubscriberAttributes): Result<Unit> = runCatching {
        if (attributes.isEmpty) return@runCatching
        val purchases = Purchases.sharedInstance
        attributes.email?.let(purchases::setEmail)
        attributes.displayName?.let(purchases::setDisplayName)
        attributes.phoneNumber?.let(purchases::setPhoneNumber)
        if (attributes.custom.isNotEmpty()) purchases.setAttributes(attributes.custom)
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

    private fun ReplacementMode.toRevenueCat(): StoreReplacementMode = when (this) {
        ReplacementMode.CHARGE_PRORATED_PRICE -> StoreReplacementMode.CHARGE_PRORATED_PRICE
        ReplacementMode.WITH_TIME_PRORATION -> StoreReplacementMode.WITH_TIME_PRORATION
        ReplacementMode.WITHOUT_PRORATION -> StoreReplacementMode.WITHOUT_PRORATION
        ReplacementMode.CHARGE_FULL_PRICE -> StoreReplacementMode.CHARGE_FULL_PRICE
        ReplacementMode.DEFERRED -> StoreReplacementMode.DEFERRED
    }

    private fun StoreProduct.toProduct(productType: ProductType): Product {
        // GoogleStoreProduct splits the id for us; other stores have no base
        // plans, so the whole id is the product id.
        val google = this as? GoogleStoreProduct
        return Product(
            id = id,
            productId = google?.productId ?: id,
            basePlanId = google?.basePlanId,
            type = productType,
            title = title,
            description = description,
            price = price.toPrice(),
            billingPeriod = period?.toPeriod(),
            // The store's own trial lives on the free-trial offer of the base
            // plan, not on the product itself.
            freeTrialPeriod = subscriptionOptions?.freeTrial?.freePhase?.billingPeriod?.toPeriod(),
        )
    }

    private fun RcPrice.toPrice(): Price = Price(
        formatted = formatted,
        amountMicros = amountMicros,
        currencyCode = currencyCode,
    )

    private fun RcPeriod.toPeriod(): Period = Period(
        value = value,
        unit = when (unit) {
            RcPeriod.Unit.DAY -> Period.Unit.DAY
            RcPeriod.Unit.WEEK -> Period.Unit.WEEK
            RcPeriod.Unit.MONTH -> Period.Unit.MONTH
            RcPeriod.Unit.YEAR -> Period.Unit.YEAR
            else -> Period.Unit.UNKNOWN
        },
        iso8601 = iso8601,
    )

    private fun StoreProduct.toReceipt(
        appUserId: String,
        productType: ProductType,
        transaction: StoreTransaction,
    ): Receipt = Receipt(
        appUserId = appUserId,
        productId = id,
        productType = productType,
        transactionId = transaction.orderId,
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
        // RC 10+ guarantees latestPurchaseDate is non-null; the Entitlement
        // field stays nullable so custom providers without this guarantee can
        // still surface "unknown".
        purchasedAtSeconds = latestPurchaseDate.time / 1000L,
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

    private companion object {
        const val TAG = "RevenueCatProvider"

        /** Play's `productId:basePlanId` separator, as used in `StoreProduct.id`. */
        const val BASE_PLAN_SEPARATOR = ":"
    }
}
