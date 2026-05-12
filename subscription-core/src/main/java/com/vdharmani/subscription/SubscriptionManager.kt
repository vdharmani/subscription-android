package com.vdharmani.subscription

import android.content.Context

/**
 * Static facade for one-time [BillingProvider] registration.
 *
 * Call [initialize] exactly once from your `Application.onCreate`. After that
 * point, [SubscriptionClient] / `ComposeSubscription` default to the registered
 * provider, so no other code in your app needs to mention which SDK is
 * underneath. To swap providers later, change one line in `Application.onCreate`.
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         SubscriptionManager.initialize(
 *             context = this,
 *             provider = RevenueCatProvider(this, BuildConfig.REVENUECAT_KEY),
 *         )
 *     }
 * }
 * ```
 */
object SubscriptionManager {

    @Volatile
    private var registered: BillingProvider? = null

    /**
     * Register [provider] as the single [BillingProvider] for the app.
     * Subsequent calls overwrite the previous registration (useful in tests).
     *
     * [context] is accepted for symmetry with similar facades and to keep
     * the door open for future configuration; the current implementation
     * does not retain it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context, provider: BillingProvider) {
        registered = provider
    }

    /**
     * Returns the registered [BillingProvider]. Throws
     * [ProviderNotInitializedException] if [initialize] has not been called.
     */
    fun provider(): BillingProvider =
        registered ?: throw ProviderNotInitializedException()

    /** `true` once [initialize] has run successfully. */
    val isInitialized: Boolean
        get() = registered != null

    /** Test hook: drop the current registration. Not for production use. */
    internal fun reset() {
        registered = null
    }
}
