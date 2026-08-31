package com.vdharmani.subscription.messages

/**
 * A resolved, ready-to-show piece of user-facing copy.
 *
 * [title] is null for messages that belong inline on a screen rather than in a
 * dialog — the Manage Subscription status line, for instance.
 */
data class SubscriptionMessage(
    val title: String?,
    val body: String,
)
