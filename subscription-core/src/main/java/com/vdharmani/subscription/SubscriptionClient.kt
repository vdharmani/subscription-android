package com.vdharmani.subscription

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.vdharmani.subscription.internal.entitlementForProduct
import com.vdharmani.subscription.internal.planChangeEligibility
import com.vdharmani.subscription.internal.playStoreInstallerCheck
import com.vdharmani.subscription.internal.toRestoreOutcome
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.PlanChangeEligibility
import com.vdharmani.subscription.model.Product
import com.vdharmani.subscription.model.ProductType
import com.vdharmani.subscription.model.Receipt
import com.vdharmani.subscription.model.ReplacementMode
import com.vdharmani.subscription.model.RestoreOutcome
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

        /**
         * When `true` (the default), [changeSubscription] checks that the plan
         * can actually be switched from this device and this store account
         * before opening the purchase sheet, failing with
         * [PlanChangeUnavailableException] instead.
         *
         * Letting an impossible switch through is worse than it looks: Google
         * Play rejects it with a developer error, and the App Store silently
         * turns it into a second, full-price subscription billing a second
         * store account. The check costs one customer-info lookup and fails
         * open if that lookup fails.
         */
        val guardPlanChanges: Boolean = true,
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
        replacementMode: ReplacementMode = ReplacementMode.CHARGE_FULL_PRICE,
    ): Result<Receipt> {
        if (config.requirePlayStoreInstaller) {
            playStoreInstallerCheck(activity)?.let { return Result.failure(it) }
        }
        if (config.guardPlanChanges) {
            val eligibility = planChangeEligibility(oldProductId)
            if (eligibility is PlanChangeEligibility.Blocked) {
                return Result.failure(
                    PlanChangeUnavailableException(eligibility.reason, eligibility.store),
                )
            }
        }
        return provider.changeSubscription(activity, productId, oldProductId, replacementMode)
    }

    /**
     * Whether the subscription behind [oldProductId] can be switched from this
     * device, on the store account signed in right now.
     *
     * Call it when building the Manage Subscription screen: an [PlanChangeEligibility.Blocked]
     * result is what turns the upgrade button into an explanatory line, which
     * `SubscriptionMessages.planChangeBlocked` renders. Access is unaffected
     * either way — a blocked switch still means a perfectly valid subscription.
     */
    suspend fun planChangeEligibility(oldProductId: String): PlanChangeEligibility =
        provider.planChangeEligibility { it.entitlementForProduct(oldProductId) }

    /**
     * Same decision as [planChangeEligibility], addressed by entitlement
     * identifier (e.g. `"premium"`) rather than by product id.
     */
    suspend fun planChangeEligibilityFor(entitlementId: String): PlanChangeEligibility =
        provider.planChangeEligibility { it.entitlement(entitlementId) }

    /**
     * Store-localised products for the paywall. See [BillingProvider.products].
     */
    suspend fun products(
        productIds: List<String>,
        productType: ProductType = ProductType.SUBS,
    ): Result<List<Product>> = provider.products(productIds, productType)

    suspend fun restore(): Result<CustomerInfo> = provider.restore()

    /**
     * Restore, classified: distinguishes "the store had nothing" from "this
     * subscription is already linked to a different app account", which
     * [restore] collapses into an anonymous failure.
     *
     * This is the call to make behind the "Restore Purchases" button, and on
     * launch, login, and **app foreground** — the store account can change
     * while the app is backgrounded, and a silent restore on resume is what
     * keeps a stale upgrade button off the screen.
     *
     * Pass the result to `SubscriptionMessages.forRestore` for the copy.
     */
    suspend fun restorePurchases(): RestoreOutcome = provider.restore().toRestoreOutcome()

    /**
     * Open the store screen where the user can cancel, resume, or fix payment
     * on their subscription. Returns `false` when no app could handle it.
     *
     * Every message that ends "…in the Play Store" needs this route: the app
     * cannot cancel, resume, or re-plan a store subscription on the user's
     * behalf. Passing [customerInfo] lets it target the store that is actually
     * billing, which for a user who subscribed on iOS is not Google Play.
     */
    fun openManageSubscription(
        productId: String? = null,
        customerInfo: CustomerInfo? = null,
    ): Boolean = ManageSubscription.open(
        context = activity,
        managementUrl = customerInfo?.managementUrl,
        productId = productId,
    )
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
        replacementMode: ReplacementMode = ReplacementMode.CHARGE_FULL_PRICE,
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

    fun restorePurchases(onResult: (RestoreOutcome) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(restorePurchases()) }
    }

    fun planChangeEligibility(oldProductId: String, onResult: (PlanChangeEligibility) -> Unit) {
        lifecycleOwner.lifecycleScope.launch { onResult(planChangeEligibility(oldProductId)) }
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
