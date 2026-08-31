package com.vdharmani.subscription.messages

import android.content.Context
import android.text.format.DateFormat
import com.vdharmani.subscription.AlreadyOwnedException
import com.vdharmani.subscription.BillingNetworkException
import com.vdharmani.subscription.PaymentDeclinedException
import com.vdharmani.subscription.PlanChangeUnavailableException
import com.vdharmani.subscription.PlayStoreInstallRequiredException
import com.vdharmani.subscription.ProductUnavailableException
import com.vdharmani.subscription.PurchaseCancelledException
import com.vdharmani.subscription.R
import com.vdharmani.subscription.StoreProblemException
import com.vdharmani.subscription.SubscriptionAlreadyLinkedException
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.PlanChangeEligibility
import com.vdharmani.subscription.model.RestoreOutcome
import com.vdharmani.subscription.model.Store
import com.vdharmani.subscription.model.SubscriptionStatus
import java.util.Date

/**
 * Turns the library's typed outcomes into the copy to put in front of the user.
 *
 * Every string comes from `res/values/strings.xml` in this module, so an app
 * changes the wording by redeclaring the same resource name and translates it
 * by adding its own `values-<locale>` — no fork, no string tables copied
 * between projects.
 *
 * The point of routing through here rather than showing `throwable.message` is
 * that SDK messages are written for developers ("ITEM_ALREADY_OWNED"), leak
 * implementation detail, and are never localised.
 */
object SubscriptionMessages {

    /**
     * Copy for a failed billing call, or **null when there is nothing to show**
     * — that is what a user dismissing the purchase sheet produces, and showing
     * an error dialog for it is the single most common mistake in this area.
     *
     * `null` means "say nothing", not "unknown"; unrecognised failures fall
     * back to a generic message rather than returning null.
     */
    fun forError(context: Context, error: Throwable): SubscriptionMessage? = when (error) {
        is PurchaseCancelledException -> null

        // Must precede AlreadyOwnedException, which it extends: "somebody
        // else's account owns this" and "you own this" need different copy.
        is SubscriptionAlreadyLinkedException -> SubscriptionMessage(
            title = context.getString(R.string.subscription_conflict_title),
            body = context.getString(R.string.subscription_conflict_message),
        )

        is PlanChangeUnavailableException -> SubscriptionMessage(
            title = null,
            body = planChangeBlockedBody(context, error.reason, error.store),
        )

        is PlayStoreInstallRequiredException -> genericError(context, R.string.subscription_error_not_play_store)
        is BillingNetworkException -> genericError(context, R.string.subscription_error_network)
        is PaymentDeclinedException -> genericError(context, R.string.subscription_error_payment_declined)
        is ProductUnavailableException -> genericError(context, R.string.subscription_error_product_unavailable)
        is AlreadyOwnedException -> genericError(context, R.string.subscription_error_already_owned)
        is StoreProblemException -> genericError(context, R.string.subscription_error_store_problem)
        else -> genericError(context, R.string.subscription_error_unknown)
    }

    /**
     * Copy for an entitlement whose state the user needs to know about —
     * payment failure, hold, pause, refund.
     *
     * Returns null for the states that speak for themselves ([SubscriptionStatus.ACTIVE],
     * [SubscriptionStatus.CANCELLED], [SubscriptionStatus.EXPIRED]): an active
     * subscription needs no banner, and a cancelled one still grants access
     * until it lapses, so warning about it would be wrong.
     *
     * These are four distinct states and deliberately do not share a title. A
     * hold is a payment failure the user can fix, a pause is something they
     * chose and that will undo itself, and a refund is final.
     */
    fun forEntitlement(context: Context, entitlement: Entitlement): SubscriptionMessage? =
        when (entitlement.status) {
            SubscriptionStatus.IN_GRACE_PERIOD -> SubscriptionMessage(
                title = context.getString(R.string.subscription_grace_title),
                body = context.getString(R.string.subscription_grace_message),
            )

            SubscriptionStatus.ON_HOLD -> SubscriptionMessage(
                title = context.getString(R.string.subscription_hold_title),
                body = context.getString(R.string.subscription_hold_message),
            )

            SubscriptionStatus.PAUSED -> SubscriptionMessage(
                title = context.getString(R.string.subscription_paused_title),
                body = entitlement.autoResumeAtSeconds
                    ?.let {
                        context.getString(
                            R.string.subscription_paused_message,
                            formatDate(context, it),
                        )
                    }
                    ?: context.getString(R.string.subscription_paused_message_no_date),
            )

            SubscriptionStatus.REFUNDED -> SubscriptionMessage(
                title = context.getString(R.string.subscription_refunded_title),
                body = context.getString(R.string.subscription_refunded_message),
            )

            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.CANCELLED,
            SubscriptionStatus.EXPIRED,
            -> null
        }

    /**
     * The line to put under the plan name on the Manage Subscription screen
     * when the plan cannot be changed from this device. Carries no title — it
     * belongs inline next to the plan, not in a dialog.
     */
    fun planChangeBlocked(
        context: Context,
        blocked: PlanChangeEligibility.Blocked,
    ): SubscriptionMessage = SubscriptionMessage(
        title = null,
        body = planChangeBlockedBody(context, blocked.reason, blocked.store),
    )

    /**
     * Case 3 — copy for the account-deletion confirmation, or **null when no
     * billing warning is warranted**.
     *
     * Null means "show your plain deletion confirmation": nothing is set to
     * renew, so promising the user that billing continues would be false. It
     * never means "block the deletion" — an app that supports account creation
     * has to let the user delete from inside the app, and the store
     * subscription is not the app's to cancel on their behalf. Pair this with
     * `SubscriptionClient.openManageSubscription` so the user can actually go
     * and cancel.
     *
     * Name the confirm/dismiss buttons yourself. One caution worth keeping:
     * don't label the dismiss button "Cancel" — the body already uses "cancel"
     * to mean cancelling the subscription, so the same word on a button that
     * does the opposite is a misread waiting to happen.
     */
    fun accountDeletion(context: Context, customerInfo: CustomerInfo): SubscriptionMessage? =
        if (customerInfo.hasRenewingSubscription) {
            SubscriptionMessage(
                title = context.getString(R.string.subscription_delete_account_title),
                body = context.getString(R.string.subscription_delete_account_message),
            )
        } else {
            null
        }

    /**
     * The auto-renewal disclosure to show before the user confirms a purchase,
     * as plain text with both link labels already inlined.
     *
     * Rendering is yours: locate [termsLabel] and [privacyLabel] in the
     * returned string and span them however your design system wants.
     */
    fun disclosure(context: Context): String = context.getString(
        R.string.subscription_disclosure,
        termsLabel(context),
        privacyLabel(context),
    )

    /**
     * The exact substring inside [disclosure] that must link to your Terms of
     * Use. Read it from here rather than hardcoding "Terms of Use", so the link
     * still lands on the right words once the string is translated.
     */
    fun termsLabel(context: Context): String =
        context.getString(R.string.subscription_terms_label)

    /** The substring inside [disclosure] that must link to your Privacy Policy. */
    fun privacyLabel(context: Context): String =
        context.getString(R.string.subscription_privacy_label)

    /**
     * Copy for a finished restore, or null when it restored something and the
     * caller should just grant access.
     */
    fun forRestore(context: Context, outcome: RestoreOutcome): SubscriptionMessage? = when (outcome) {
        is RestoreOutcome.Restored -> null

        is RestoreOutcome.NothingToRestore -> SubscriptionMessage(
            title = context.getString(R.string.subscription_restore_none_found_title),
            body = context.getString(R.string.subscription_restore_none_found_message),
        )

        is RestoreOutcome.LinkedToAnotherAccount -> SubscriptionMessage(
            title = context.getString(R.string.subscription_conflict_title),
            body = context.getString(R.string.subscription_conflict_message),
        )

        is RestoreOutcome.Failed -> forError(context, outcome.error)
    }

    private fun planChangeBlockedBody(
        context: Context,
        reason: PlanChangeEligibility.Reason,
        store: Store,
    ): String = when (reason) {
        PlanChangeEligibility.Reason.CROSS_PLATFORM ->
            if (store.isApple) {
                context.getString(R.string.subscription_plan_change_apple)
            } else {
                context.getString(R.string.subscription_plan_change_other_store)
            }

        PlanChangeEligibility.Reason.STORE_ACCOUNT_MISMATCH ->
            context.getString(R.string.subscription_plan_change_account_mismatch)

        PlanChangeEligibility.Reason.SUBSCRIPTION_NOT_ACTIVE ->
            context.getString(R.string.subscription_plan_change_not_active)

        PlanChangeEligibility.Reason.NO_ACTIVE_SUBSCRIPTION ->
            context.getString(R.string.subscription_plan_change_none)
    }

    private fun genericError(context: Context, bodyRes: Int) = SubscriptionMessage(
        title = context.getString(R.string.subscription_error_title),
        body = context.getString(bodyRes),
    )

    private fun formatDate(context: Context, unixSeconds: Long): String =
        DateFormat.getDateFormat(context).format(Date(unixSeconds * 1000L))
}
