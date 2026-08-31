package com.vdharmani.subscription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vdharmani.subscription.messages.SubscriptionMessage.Display
import com.vdharmani.subscription.messages.SubscriptionMessages
import com.vdharmani.subscription.model.CustomerInfo
import com.vdharmani.subscription.model.Entitlement
import com.vdharmani.subscription.model.PlanChangeEligibility
import com.vdharmani.subscription.model.RestoreOutcome
import com.vdharmani.subscription.model.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The message layer resolved through real Android resources.
 *
 * The JVM conformance test proves the strings are *in* `strings.xml`; only
 * this proves the resolvers actually reach them, that the format arguments
 * line up, and that a wrong `getString` overload isn't quietly producing
 * "%1$s" on screen.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionMessagesInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun purchaseFailuresResolveToTheirAppSpecStrings() {
        assertMessage(
            PaymentDeclinedException(),
            "Payment couldn't be completed. Please check your payment method and try again.",
            Display.DIALOG, "PUR-2EBD",
        )
        assertMessage(
            PaymentPendingException(),
            "Your purchase is being processed. We'll unlock access as soon as it's confirmed.",
            Display.TOAST, "PUR-8A70",
        )
        assertMessage(
            AlreadyOwnedException(),
            "You already have an active subscription.",
            Display.DIALOG, "PUR-14F7",
        )
        assertMessage(
            SubscriptionAlreadyLinkedException(),
            "An active subscription on this Google Play account is already linked to a " +
                "different account in the app. Sign in to that account to use it.",
            Display.DIALOG, "LNK-9C34",
        )
        assertMessage(
            BillingNetworkException(),
            "Please check your internet connection.",
            Display.TOAST, "PAY-60EC",
        )
        assertMessage(
            SecureConnectionException(),
            "We couldn't establish a secure connection. Please try again later.",
            Display.DIALOG, "SIN-ADA3",
        )
        assertMessage(
            ReceiptValidationException(),
            "We couldn't verify your purchase. Please try Restore Purchases.",
            Display.DIALOG, "PUR-4140",
        )
        assertMessage(
            TrialNotEligibleException(),
            "You're not eligible for a free trial.",
            Display.DIALOG, "TRL-7B6A",
        )
    }

    @Test
    fun aCancelledSheetSaysNothing() {
        assertNull(SubscriptionMessages.forError(context, PurchaseCancelledException()))
    }

    @Test
    fun anUnknownFailureFallsBackRatherThanGoingSilent() {
        assertMessage(
            IllegalStateException("something nobody mapped"),
            "Something went wrong. Please try again.",
            Display.TOAST, "PAY-18D3",
        )
    }

    @Test
    fun lifecycleStatesResolveWithTheirDatesSubstituted() {
        val hold = SubscriptionMessages.forEntitlement(
            context,
            entitlement(isActive = false, willRenew = false, billingIssueDetectedAtSeconds = 1L),
        )!!
        assertEquals(
            "Payment issue — access suspended. Update your payment method in the Play Store " +
                "to restore access.",
            hold.body,
        )
        assertEquals(Display.DIALOG, hold.display)

        val paused = SubscriptionMessages.forEntitlement(
            context,
            entitlement(isActive = false, autoResumeAtSeconds = RESUME_AT),
        )!!
        // The real check: the date argument actually landed in the string.
        assertTrue("unsubstituted placeholder: ${paused.body}", !paused.body.contains("%1"))
        assertTrue(paused.body.startsWith("Your subscription is paused and will resume on "))
        assertTrue(paused.body.endsWith("Resume it in the Play Store to regain access now."))
        assertEquals(Display.BANNER, paused.display)

        val cancelled = SubscriptionMessages.forEntitlement(
            context,
            entitlement(willRenew = false),
        )!!
        assertTrue(cancelled.body.startsWith("Your subscription ends on "))
        assertTrue(!cancelled.body.contains("%1"))

        val expired = SubscriptionMessages.forEntitlement(
            context,
            entitlement(isActive = false, willRenew = false),
        )!!
        assertEquals("Your subscription has ended. Subscribe again to continue.", expired.body)
        assertEquals(Display.PAYWALL, expired.display)

        assertNull(SubscriptionMessages.forEntitlement(context, entitlement()))
    }

    @Test
    fun restoreOutcomesResolve() {
        val info = CustomerInfo("u", emptyList(), emptySet())
        assertEquals(
            "No purchases found.",
            SubscriptionMessages.forRestore(context, RestoreOutcome.NothingToRestore(info))!!.body,
        )
        assertEquals(
            "Your subscription has been restored.",
            SubscriptionMessages.forRestore(context, RestoreOutcome.Restored(info))!!.body,
        )
        assertEquals(
            "This subscription belongs to another account.",
            SubscriptionMessages.forRestore(
                context,
                RestoreOutcome.LinkedToAnotherAccount(SubscriptionAlreadyLinkedException()),
            )!!.body,
        )
    }

    @Test
    fun planChangeCopyVariesByStoreAndGoesSilentWhereAppSpecHasNoString() {
        fun blocked(reason: PlanChangeEligibility.Reason, store: Store) =
            SubscriptionMessages.planChangeBlocked(
                context,
                PlanChangeEligibility.Blocked(reason, store),
            )

        assertEquals(
            "Purchased on iPhone through the App Store. To change or cancel your plan, open " +
                "the app on an iOS device.",
            blocked(PlanChangeEligibility.Reason.CROSS_PLATFORM, Store.APP_STORE)!!.body,
        )
        assertEquals(
            "Purchased on the web. Manage your subscription from your account on our website.",
            blocked(PlanChangeEligibility.Reason.CROSS_PLATFORM, Store.STRIPE)!!.body,
        )
        assertEquals(
            "Purchased through the Play Store. Manage it there to change or cancel your plan.",
            blocked(PlanChangeEligibility.Reason.CROSS_PLATFORM, Store.AMAZON)!!.body,
        )
        assertEquals(
            "Purchased with a different Google account. Sign in to that account in the Play " +
                "Store to upgrade or change your plan.",
            blocked(PlanChangeEligibility.Reason.STORE_ACCOUNT_MISMATCH, Store.PLAY_STORE)!!.body,
        )
        // AppSpec specifies no copy for these.
        assertNull(blocked(PlanChangeEligibility.Reason.NO_ACTIVE_SUBSCRIPTION, Store.PLAY_STORE))
        assertNull(blocked(PlanChangeEligibility.Reason.SUBSCRIPTION_NOT_ACTIVE, Store.PLAY_STORE))
    }

    @Test
    fun accountDeletionWarnsOnlyWhileSomethingRenews() {
        val renewing = CustomerInfo("u", listOf(entitlement()), emptySet())
        assertEquals(
            "Deleting won't cancel your subscription. Cancel it in the Play Store to stop " +
                "future charges.",
            SubscriptionMessages.accountDeletion(context, renewing)!!.body,
        )

        val cancelled = CustomerInfo("u", listOf(entitlement(willRenew = false)), emptySet())
        assertNull(SubscriptionMessages.accountDeletion(context, cancelled))
    }

    @Test
    fun theDisclosureRendersWithBothLinkLabelsInPlace() {
        val text = SubscriptionMessages.disclosure(context)
        val terms = SubscriptionMessages.termsLabel(context)
        val privacy = SubscriptionMessages.privacyLabel(context)

        assertEquals(
            "Subscription auto-renews unless cancelled at least 24 hours before the current " +
                "period ends. Manage or cancel anytime in Google Play > Subscriptions. " +
                "By subscribing you agree to our Terms of Use and Privacy Policy.",
            text,
        )
        // Both labels must be locatable, or an app cannot span the links.
        assertTrue(text.indexOf(terms) >= 0)
        assertTrue(text.indexOf(privacy) > text.indexOf(terms))
    }

    @Test
    fun theTrialDisclosureSubstitutesAllThreeArguments() {
        val text = SubscriptionMessages.trialDisclosure(context, 7, "₹1,600.00", "year")

        assertEquals("Free for 7 days, then ₹1,600.00 per year. Cancel anytime.", text)
    }

    @Test
    fun offlineIsABannerNotAToast() {
        val m = SubscriptionMessages.offline(context)

        assertEquals("You're offline — showing saved data.", m.body)
        // AppSpec: ongoing state, so it must not re-fire as a toast on retry.
        assertEquals(Display.BANNER, m.display)
    }

    private fun assertMessage(
        error: Throwable,
        expectedBody: String,
        expectedDisplay: Display,
        expectedCaseId: String,
    ) {
        val m = SubscriptionMessages.forError(context, error)
            ?: throw AssertionError("no message for ${error::class.simpleName}")
        assertEquals(expectedBody, m.body)
        assertEquals(expectedDisplay, m.display)
        assertEquals(expectedCaseId, m.caseId)
    }

    private fun entitlement(
        isActive: Boolean = true,
        willRenew: Boolean = true,
        billingIssueDetectedAtSeconds: Long? = null,
        autoResumeAtSeconds: Long? = null,
    ) = Entitlement(
        identifier = "premium",
        productId = "premium",
        purchasedAtSeconds = 1_700_000_000L,
        expiresAtSeconds = 1_800_000_000L,
        willRenew = willRenew,
        isInGracePeriod = false,
        basePlanId = "monthly",
        isActive = isActive,
        store = Store.PLAY_STORE,
        billingIssueDetectedAtSeconds = billingIssueDetectedAtSeconds,
        autoResumeAtSeconds = autoResumeAtSeconds,
    )

    private companion object {
        const val RESUME_AT = 1_800_000_000L
    }
}
