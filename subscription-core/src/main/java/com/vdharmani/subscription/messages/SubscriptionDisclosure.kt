package com.vdharmani.subscription.messages

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import androidx.annotation.ColorInt
import com.vdharmani.subscription.R

/**
 * The auto-renewal disclosure for the paywall, with "Terms of Use" and
 * "Privacy Policy" as working links.
 *
 * Both stores require the disclosure before the user confirms a purchase, and
 * the two link targets have to be reachable from it. Building the spans from
 * separate label resources rather than searching the sentence for hardcoded
 * English keeps the links working in translation, where the labels move,
 * change length, or take a different grammatical form.
 */
object SubscriptionDisclosure {

    /** The disclosure as plain text, links included but not styled. */
    fun text(context: Context): String = context.getString(
        R.string.subscription_disclosure,
        context.getString(R.string.subscription_terms_label),
        context.getString(R.string.subscription_privacy_label),
    )

    /**
     * The disclosure for a `TextView`, with both labels styled in [linkColor],
     * bold and underlined, and wired to the callbacks.
     *
     * The `TextView` also needs `movementMethod = LinkMovementMethod.getInstance()`,
     * or the spans render but never fire.
     */
    fun spanned(
        context: Context,
        @ColorInt linkColor: Int,
        onTermsClick: () -> Unit,
        onPrivacyClick: () -> Unit,
    ): CharSequence {
        val terms = context.getString(R.string.subscription_terms_label)
        val privacy = context.getString(R.string.subscription_privacy_label)
        val builder = SpannableStringBuilder(
            context.getString(R.string.subscription_disclosure, terms, privacy),
        )
        builder.linkify(terms, linkColor, onTermsClick)
        builder.linkify(privacy, linkColor, onPrivacyClick)
        return builder
    }

    private fun SpannableStringBuilder.linkify(
        label: String,
        @ColorInt linkColor: Int,
        onClick: () -> Unit,
    ) {
        val start = indexOf(label).takeIf { it >= 0 } ?: return
        val end = start + label.length
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onClick()

                // ClickableSpan paints its own underline and theme accent
                // colour by default, which would override the app's.
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false
                }
            },
            start,
            end,
            flag,
        )
        setSpan(ForegroundColorSpan(linkColor), start, end, flag)
        setSpan(StyleSpan(Typeface.BOLD), start, end, flag)
        setSpan(UnderlineSpan(), start, end, flag)
    }
}
