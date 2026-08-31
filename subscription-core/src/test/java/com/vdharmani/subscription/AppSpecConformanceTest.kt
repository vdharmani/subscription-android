package com.vdharmani.subscription

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards AppSpec's Copy Standards rule: *"Every message here must be reachable
 * in the build and match word for word."*
 *
 * The expected strings below are quoted from AppSpec and keyed by case id. If
 * someone reworths a string — or "improves" one — this fails and names the
 * case. That is the point: the copy is a contract with QA, not a preference.
 *
 * Read straight from the resource XML rather than through `Context`, so the
 * check stays a plain JVM test with no Robolectric dependency.
 */
class AppSpecConformanceTest {

    @Test
    fun `every AppSpec message is present word for word`() {
        val strings = loadStrings()
        val mismatches = expected.mapNotNull { (name, spec) ->
            val actual = strings[name]
            when {
                actual == null -> "$name — MISSING (AppSpec: \"$spec\")"
                actual != spec -> "$name\n     spec: \"$spec\"\n     code: \"$actual\""
                else -> null
            }
        }
        assertTrue(
            "${mismatches.size} string(s) do not match AppSpec:\n  " +
                mismatches.joinToString("\n  "),
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `the Android disclosure renders exactly as AppSpec PAY-B845`() {
        val s = loadStrings()
        val rendered = s.getValue("subscription_disclosure")
            .replace("%1\$s", s.getValue("subscription_terms_label"))
            .replace("%2\$s", s.getValue("subscription_privacy_label"))

        assertEquals(
            "Subscription auto-renews unless cancelled at least 24 hours before the current " +
                "period ends. Manage or cancel anytime in Google Play > Subscriptions. " +
                "By subscribing you agree to our Terms of Use and Privacy Policy.",
            rendered,
        )
    }

    @Test
    fun `no string carries an invented dialog title`() {
        // AppSpec defines one string per case; a "_title" resource would mean
        // the library had invented copy that QA never signed off.
        val titles = loadStrings().keys.filter { it.endsWith("_title") }

        assertEquals(emptyList<String>(), titles)
    }

    private fun loadStrings(): Map<String, String> {
        val xml = sequenceOf(
            File("src/main/res/values/strings.xml"),
            File("subscription-core/src/main/res/values/strings.xml"),
        ).firstOrNull { it.exists() } ?: error("strings.xml not found from ${File(".").absolutePath}")

        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml.readText())
            .associate { m ->
                m.groupValues[1] to m.groupValues[2]
                    .replace("\\'", "'")
                    .replace("&gt;", ">")
                    .replace("&lt;", "<")
                    .replace("&amp;", "&")
            }
    }

    private companion object {
        /** name -> the exact AppSpec string, Android variant where it differs. */
        val expected = mapOf(
            // Purchase flow
            "subscription_purchase_activated" to "Subscription activated.",
            "subscription_purchase_pending" to
                "Your purchase is being processed. We'll unlock access as soon as it's confirmed.",
            "subscription_purchase_awaiting_approval" to
                "Your purchase is waiting for approval. We'll unlock access as soon as it's approved.",
            "subscription_payment_declined" to
                "Payment couldn't be completed. Please check your payment method and try again.",
            "subscription_already_active" to "You already have an active subscription.",
            "subscription_receipt_unverified" to
                "We couldn't verify your purchase. Please try Restore Purchases.",
            // Trials & offers
            "subscription_trial_started" to "Your free trial has started.",
            "subscription_trial_ends" to "Your trial ends on %1\$s.",
            "subscription_trial_not_eligible" to "You're not eligible for a free trial.",
            "subscription_offer_unavailable" to
                "This offer isn't available on your account. You can still subscribe at the standard price.",
            "subscription_price_change" to
                "The price of your subscription is changing. Confirm by %1\$s to keep your access.",
            "subscription_trial_disclosure" to
                "Free for %1\$s days, then %2\$s per %3\$s. Cancel anytime.",
            // Linkage
            "subscription_conflict" to
                "An active subscription on this Google Play account is already linked to a " +
                "different account in the app. Sign in to that account to use it.",
            "subscription_conflict_short" to "This subscription belongs to another account.",
            "subscription_delete_account" to
                "Deleting won't cancel your subscription. Cancel it in the Play Store to stop " +
                "future charges.",
            "subscription_plan_change_account_mismatch" to
                "Purchased with a different Google account. Sign in to that account in the Play " +
                "Store to upgrade or change your plan.",
            "subscription_plan_change_apple" to
                "Purchased on iPhone through the App Store. To change or cancel your plan, open " +
                "the app on an iOS device.",
            "subscription_plan_change_web" to
                "Purchased on the web. Manage your subscription from your account on our website.",
            "subscription_plan_change_provider" to
                "Purchased through %1\$s. Manage it there to change or cancel your plan.",
            // Lifecycle
            "subscription_ends_on" to "Your subscription ends on %1\$s.",
            "subscription_grace" to
                "Payment issue — update your payment method to keep your access.",
            "subscription_hold" to
                "Payment issue — access suspended. Update your payment method in the Play Store " +
                "to restore access.",
            "subscription_paused" to
                "Your subscription is paused and will resume on %1\$s. Resume it in the Play " +
                "Store to regain access now.",
            "subscription_expired" to "Your subscription has ended. Subscribe again to continue.",
            "subscription_refunded" to
                "Your subscription was refunded and access has ended. You can subscribe again any time.",
            // Restore
            "subscription_restore_none" to "No purchases found.",
            "subscription_restored" to "Your subscription has been restored.",
            // Connectivity
            "subscription_no_internet" to "Please check your internet connection.",
            "subscription_secure_connection_failed" to
                "We couldn't establish a secure connection. Please try again later.",
            "subscription_offline_saved_data" to "You're offline — showing saved data.",
            "subscription_generic_error" to "Something went wrong. Please try again.",
        )
    }
}
