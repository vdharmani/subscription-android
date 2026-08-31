package com.vdharmani.subscription.messages

import android.content.Context
import android.text.format.DateFormat
import com.vdharmani.subscription.AlreadyOwnedException
import com.vdharmani.subscription.BillingNetworkException
import com.vdharmani.subscription.OfferUnavailableException
import com.vdharmani.subscription.PaymentDeclinedException
import com.vdharmani.subscription.PaymentPendingException
import com.vdharmani.subscription.PlanChangeUnavailableException
import com.vdharmani.subscription.PurchaseCancelledException
import com.vdharmani.subscription.R
import com.vdharmani.subscription.ReceiptValidationException
import com.vdharmani.subscription.SecureConnectionException
import com.vdharmani.subscription.SubscriptionAlreadyLinkedException
import com.vdharmani.subscription.TrialNotEligibleException
import com.vdharmani.subscription.messages.SubscriptionMessage.Display
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.PeriodType
import com.vdharmani.subscription.model.PlanChangeEligibility
import com.vdharmani.subscription.model.RestoreOutcome
import com.vdharmani.subscription.model.Store
import com.vdharmani.subscription.model.SubscriptionStatus
import java.util.Date

/**
 * Resolves every subscription outcome to the message AppSpec specifies for it.
 *
 * Each returned [SubscriptionMessage] carries the AppSpec case id it came from,
 * so a string on screen can be traced to the row QA signs off against.
 *
 * A `null` return always means **say nothing** — a cancelled purchase sheet, a
 * healthy subscription — never "unknown". Unrecognised failures fall back to
 * the generic message rather than going silent.
 *
 * Strings come from this module's `res/values/strings.xml` and are quoted word
 * for word from AppSpec; override by redeclaring a name in your app.
 */
object SubscriptionMessages {

    // -- purchase ---------------------------------------------------------

    /** PUR-9CD3 — a completed, validated purchase. */
    fun purchaseActivated(context: Context) =
        msg(context, R.string.subscription_purchase_activated, Display.TOAST, "PUR-9CD3")

    /** TRL-AF47 — a free trial has begun. */
    fun trialStarted(context: Context) =
        msg(context, R.string.subscription_trial_started, Display.TOAST, "TRL-AF47")

    /**
     * Copy for a failed billing call, or **null when there is nothing to show**.
     *
     * `null` is what a dismissed purchase sheet produces (PUR-DE40). Showing an
     * error there is the single most common mistake in this area.
     */
    fun forError(context: Context, error: Throwable): SubscriptionMessage? = when (error) {
        is PurchaseCancelledException -> null

        // PUR-8A70 / TRL-1C28. Pending is not a decline: the money may still
        // land. Say it is processing, grant nothing, show no error.
        is PaymentPendingException ->
            msg(context, R.string.subscription_purchase_pending, Display.TOAST, "PUR-8A70")

        // Must precede AlreadyOwnedException, which it extends.
        is SubscriptionAlreadyLinkedException ->
            msg(context, R.string.subscription_conflict, Display.DIALOG, "LNK-9C34")

        is PlanChangeUnavailableException ->
            planChangeBlocked(context, error.reason, error.store)

        is TrialNotEligibleException ->
            msg(context, R.string.subscription_trial_not_eligible, Display.DIALOG, "TRL-7B6A")

        is OfferUnavailableException ->
            msg(context, R.string.subscription_offer_unavailable, Display.DIALOG, "TRL-F5AA")

        is ReceiptValidationException ->
            msg(context, R.string.subscription_receipt_unverified, Display.DIALOG, "PUR-4140")

        is PaymentDeclinedException ->
            msg(context, R.string.subscription_payment_declined, Display.DIALOG, "PUR-2EBD")

        is AlreadyOwnedException ->
            msg(context, R.string.subscription_already_active, Display.DIALOG, "PUR-14F7")

        // Payment domains fail closed, so this is never a silent retry.
        is SecureConnectionException ->
            msg(context, R.string.subscription_secure_connection_failed, Display.DIALOG, "SIN-ADA3")

        is BillingNetworkException ->
            msg(context, R.string.subscription_no_internet, Display.TOAST, "PAY-60EC")

        // AppSpec: tell a store outage apart from user error in the logs, not
        // in the message.
        else -> msg(context, R.string.subscription_generic_error, Display.TOAST, "PAY-18D3")
    }

    // -- lifecycle --------------------------------------------------------

    /**
     * The state message for an entitlement, or null when the state speaks for
     * itself ([SubscriptionStatus.ACTIVE]).
     *
     * These are distinct states with deliberately distinct copy: a grace period
     * still has access and a hold does not; a pause is user-initiated and
     * undoes itself; a refund is final. A cancelled subscription is reassured
     * about its end date, not warned.
     */
    fun forEntitlement(context: Context, entitlement: Entitlement): SubscriptionMessage? =
        when (entitlement.status) {
            SubscriptionStatus.ACTIVE -> null

            // TRL-DDA5 while still in the trial, STA-FAA1 once paying. Both
            // reassure — cancelling is not expiry, access runs to the date.
            SubscriptionStatus.CANCELLED -> {
                val ends = entitlement.expiresAtSeconds
                if (entitlement.periodType == PeriodType.TRIAL) {
                    msg(context, R.string.subscription_trial_ends, Display.INLINE, "TRL-DDA5", ends)
                } else {
                    msg(context, R.string.subscription_ends_on, Display.INLINE, "STA-FAA1", ends)
                }
            }

            SubscriptionStatus.IN_GRACE_PERIOD ->
                msg(context, R.string.subscription_grace, Display.BANNER, "STA-4747")

            SubscriptionStatus.ON_HOLD ->
                msg(context, R.string.subscription_hold, Display.DIALOG, "STA-F9F7")

            // PAUSED is detected from the auto-resume date, so it is always
            // present here and the date never needs a fallback.
            SubscriptionStatus.PAUSED ->
                msg(
                    context, R.string.subscription_paused, Display.BANNER, "STA-E31C",
                    entitlement.autoResumeAtSeconds,
                )

            SubscriptionStatus.EXPIRED ->
                msg(context, R.string.subscription_expired, Display.PAYWALL, "STA-F01C")

            SubscriptionStatus.REFUNDED ->
                msg(context, R.string.subscription_refunded, Display.DIALOG, "STA-01B8")
        }

    /** TRL-C284 — price-change consent. [deadlineSeconds] is the confirm-by date. */
    fun priceChange(context: Context, deadlineSeconds: Long) =
        msg(context, R.string.subscription_price_change, Display.BANNER, "TRL-C284", deadlineSeconds)

    // -- plan changes -----------------------------------------------------

    /**
     * Why a plan change is not offered, or **null when AppSpec specifies no
     * message** — having nothing to change is not something to announce, so the
     * screen simply shows no upgrade button.
     */
    fun planChangeBlocked(
        context: Context,
        blocked: PlanChangeEligibility.Blocked,
    ): SubscriptionMessage? = planChangeBlocked(context, blocked.reason, blocked.store)

    // -- restore ----------------------------------------------------------

    /** Copy for a finished restore. */
    fun forRestore(context: Context, outcome: RestoreOutcome): SubscriptionMessage? = when (outcome) {
        is RestoreOutcome.Restored ->
            msg(context, R.string.subscription_restored, Display.TOAST, "RST-F792")

        is RestoreOutcome.NothingToRestore ->
            msg(context, R.string.subscription_restore_none, Display.TOAST, "RST-7441")

        is RestoreOutcome.LinkedToAnotherAccount ->
            msg(context, R.string.subscription_conflict_short, Display.DIALOG, "RST-F08E")

        // RST-31B8: never conclude "no purchases" from a failed network call.
        is RestoreOutcome.Failed -> forError(context, outcome.error)
    }

    // -- account deletion -------------------------------------------------

    /**
     * LNK-7505 / CMB-AE17 — the deletion warning, or **null when no billing
     * warning is warranted** (nothing is renewing, so promising continued
     * billing would be false; show your plain deletion confirmation instead).
     *
     * Never a block: an app that supports account creation has to let the user
     * delete from inside it, and the store subscription is not the app's to
     * cancel. Pair with `SubscriptionClient.openManageSubscription`.
     *
     * Name the buttons yourself — AppSpec's are Keep Account / Delete Anyway.
     * Do not label the dismiss button "Cancel": the body already uses "cancel"
     * to mean cancelling the subscription.
     */
    fun accountDeletion(context: Context, customerInfo: CustomerInfo): SubscriptionMessage? =
        if (customerInfo.hasRenewingSubscription) {
            msg(context, R.string.subscription_delete_account, Display.DIALOG, "LNK-7505")
        } else {
            null
        }

    // -- connectivity -----------------------------------------------------

    /**
     * SIN-B7C1 / CMB-BF26 — shown while serving a cached entitlement offline.
     * A banner, not a toast: it is an ongoing state, and a failed refresh must
     * never revoke access or read as "no subscription".
     */
    fun offline(context: Context) =
        msg(context, R.string.subscription_offline_saved_data, Display.BANNER, "SIN-B7C1")

    // -- paywall text -----------------------------------------------------

    /** PAY-B845 — the auto-renewal disclosure, with both link labels inlined. */
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
     * TRL-038F — the trial disclosure both stores require when a trial is
     * offered. Take [price] and [period] from the store's own product so the
     * figures match what will actually be charged.
     */
    fun trialDisclosure(context: Context, days: Int, price: String, period: String): String =
        context.getString(R.string.subscription_trial_disclosure, days.toString(), price, period)

    // -- internals --------------------------------------------------------

    private fun planChangeBlocked(
        context: Context,
        reason: PlanChangeEligibility.Reason,
        store: Store,
    ): SubscriptionMessage? = when (reason) {
        PlanChangeEligibility.Reason.CROSS_PLATFORM -> when {
            store.isApple ->
                msg(context, R.string.subscription_plan_change_apple, Display.INLINE, "LNK-05B7")

            store.isWeb ->
                msg(context, R.string.subscription_plan_change_web, Display.INLINE, "SMG-100B")

            else -> SubscriptionMessage(
                title = null,
                body = context.getString(
                    R.string.subscription_plan_change_provider,
                    storeLabel(context, store),
                ),
                display = Display.INLINE,
                caseId = "XPV-52B3",
            )
        }

        PlanChangeEligibility.Reason.STORE_ACCOUNT_MISMATCH -> msg(
            context, R.string.subscription_plan_change_account_mismatch, Display.INLINE, "LNK-57F2",
        )

        // AppSpec specifies no message for these: with nothing live to change,
        // the screen just doesn't offer the option. Inventing copy here would
        // break "one string per case".
        PlanChangeEligibility.Reason.NO_ACTIVE_SUBSCRIPTION,
        PlanChangeEligibility.Reason.SUBSCRIPTION_NOT_ACTIVE,
        -> null
    }

    private fun storeLabel(context: Context, store: Store): String = context.getString(
        when {
            store.isApple -> R.string.subscription_store_app_store
            store.isWeb -> R.string.subscription_store_website
            else -> R.string.subscription_store_play_store
        },
    )

    private fun msg(
        context: Context,
        bodyRes: Int,
        display: Display,
        caseId: String,
    ) = SubscriptionMessage(
        title = null,
        body = context.getString(bodyRes),
        display = display,
        caseId = caseId,
    )

    private fun msg(
        context: Context,
        bodyRes: Int,
        display: Display,
        caseId: String,
        dateSeconds: Long?,
    ) = SubscriptionMessage(
        title = null,
        body = context.getString(bodyRes, formatDate(context, dateSeconds)),
        display = display,
        caseId = caseId,
    )

    private fun formatDate(context: Context, unixSeconds: Long?): String =
        unixSeconds?.let { DateFormat.getDateFormat(context).format(Date(it * 1000L)) }.orEmpty()
}
