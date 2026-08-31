package com.vdharmani.subscription

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * Where to send a user who wants to cancel, resume, or fix payment on a
 * subscription.
 *
 * Every case that ends in "…in the Play Store" needs this: cancelling after an
 * account deletion, fixing a card in account hold, resuming a paused plan,
 * switching plans on the store account that actually owns the subscription.
 * None of those are things the app can do on the user's behalf, so the honest
 * ending to each of those messages is a working route to the store.
 */
object ManageSubscription {

    /**
     * Deep link to the Play Store's subscription screen.
     *
     * Pass [productId] to land on that specific subscription rather than the
     * user's whole list; any `":basePlanId"` suffix is stripped, because the
     * link addresses the subscription, not one of its base plans.
     */
    fun playStoreUri(packageName: String, productId: String? = null): Uri {
        val builder = PLAY_SUBSCRIPTIONS_URL.toUri().buildUpon()
        productId?.substringBefore(':')?.takeIf { it.isNotBlank() }?.let {
            builder.appendQueryParameter("sku", it)
        }
        return builder.appendQueryParameter("package", packageName).build()
    }

    /**
     * Open the store's subscription screen.
     *
     * Prefers [managementUrl] — the provider reports the store that is actually
     * billing, which for a user who subscribed on iOS is not Google Play — and
     * falls back to the Play deep link for [productId].
     *
     * Returns `false` when no activity could handle the intent (a device with
     * no Play Store, or a managed profile); show the message without a button
     * rather than crashing on a dead link.
     */
    fun open(context: Context, managementUrl: String? = null, productId: String? = null): Boolean {
        val uri = managementUrl?.takeIf { it.isNotBlank() }?.toUri()
            ?: playStoreUri(context.packageName, productId)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            // A non-Activity Context (an application context held by a
            // ViewModel, say) cannot start an activity without its own task.
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private const val PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"
}
