package com.vdharmani.subscription.revenuecat

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks Google Play what the **currently signed-in Play account** owns.
 *
 * RevenueCat reports entitlements from its own backend, which is the right
 * answer for access but says nothing about which Play account is signed in
 * right now. Only Play knows that, and only `queryPurchasesAsync` reports it —
 * which is why detecting a store-account switch (Case 5) needs a direct query.
 *
 * The client here is deliberately short-lived: connect, ask, disconnect. It is
 * only used on the plan-change path, never in a hot loop, and it never
 * outlives the call that made it.
 *
 * Every failure path returns `null` — "could not tell" — rather than an empty
 * list. An empty list is a real answer meaning "this account owns nothing"; a
 * failed connection is not, and treating the two alike would block plan
 * changes whenever Play was briefly unreachable.
 */
internal class PlayStoreOwnership(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Serialises callers so a burst of checks cannot open several connections
     * to Play at once, which is the one thing likely to disturb the connection
     * the billing SDK already holds.
     */
    private val mutex = Mutex()

    /**
     * Subscriptions the current Play account owns, or `null` when Play could
     * not be reached, refused, or did not answer in time.
     *
     * The timeout matters: this runs before the purchase sheet opens, so a
     * wedged Play connection must not leave the user staring at a dead upgrade
     * button. Giving up returns "could not tell", which lets the purchase
     * proceed.
     */
    suspend fun ownedSubscriptions(): List<Purchase>? = mutex.withLock {
        withTimeoutOrNull(QUERY_TIMEOUT_MS) { queryOwnedSubscriptions() }
    }

    private suspend fun queryOwnedSubscriptions(): List<Purchase>? {
        val client = BillingClient.newBuilder(appContext)
            // Query-only client: purchases are driven by the billing SDK, so
            // nothing is expected through this listener.
            .setListener { _, _ -> }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()

        return try {
            if (connect(client)) queryPurchases(client) else null
        } finally {
            // Also covers cancellation and the timeout above: endConnection is
            // not a suspending call, so it still runs while unwinding.
            runCatching { client.endConnection() }
        }
    }

    private suspend fun connect(client: BillingClient): Boolean =
        suspendCancellableCoroutine { continuation ->
            // Play may report setup-finished and then service-disconnected for
            // the same attempt; resuming twice would crash the caller.
            val resumed = AtomicBoolean(false)

            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resume(
                            billingResult.responseCode == BillingClient.BillingResponseCode.OK,
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    if (resumed.compareAndSet(false, true)) continuation.resume(false)
                }
            })
        }

    private suspend fun queryPurchases(client: BillingClient): List<Purchase>? =
        suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                // A subscription in account hold or paused is suspended, not
                // gone. Leaving those out would make the account that actually
                // owns the plan look like a different one, and block a
                // legitimate change while the user is fixing their card.
                .includeSuspendedSubscriptions(true)
                .build()

            val resumed = AtomicBoolean(false)
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (resumed.compareAndSet(false, true)) {
                    continuation.resume(
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            purchases
                        } else {
                            null
                        },
                    )
                }
            }
        }

    private companion object {
        /**
         * Play normally connects and answers well inside a second. This only
         * bounds the pathological case; exceeding it is treated as "could not
         * tell", never as "not owned".
         */
        const val QUERY_TIMEOUT_MS = 5_000L
    }
}
