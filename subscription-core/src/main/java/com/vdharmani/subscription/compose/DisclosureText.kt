package com.vdharmani.subscription.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.vdharmani.subscription.R

/**
 * The auto-renewal disclosure as an [AnnotatedString], with "Terms of Use" and
 * "Privacy Policy" as tappable links styled in [linkColor], bold and
 * underlined.
 *
 * Render it with `Text(disclosure)` — `LinkAnnotation.Clickable` makes Compose
 * handle the tap targets and accessibility, so no `pointerInput` or manual
 * offset maths is needed.
 *
 * ```kotlin
 * Text(
 *     text = rememberDisclosureText(
 *         linkColor = MaterialTheme.colorScheme.primary,
 *         onTermsClick = { openUrl(TERMS_URL) },
 *         onPrivacyClick = { openUrl(PRIVACY_URL) },
 *     ),
 *     style = MaterialTheme.typography.bodySmall,
 * )
 * ```
 */
@Composable
fun rememberDisclosureText(
    linkColor: Color,
    onTermsClick: () -> Unit,
    onPrivacyClick: () -> Unit,
): AnnotatedString {
    // stringResource rather than LocalContext.getString: it reads through the
    // composition's own configuration, so the text follows a locale or font
    // change instead of freezing whatever was current when this first ran.
    val terms = stringResource(R.string.subscription_terms_label)
    val privacy = stringResource(R.string.subscription_privacy_label)
    val disclosure = stringResource(R.string.subscription_disclosure, terms, privacy)

    // The click lambdas are usually recreated on every recomposition, so keying
    // the remember on them would rebuild the string every frame. Routing them
    // through rememberUpdatedState instead lets the cached AnnotatedString hold
    // a stable reference that still reads the newest lambda when tapped — a
    // plain capture here would freeze whichever pair was current on first
    // composition and keep calling those forever.
    val currentOnTerms by rememberUpdatedState(onTermsClick)
    val currentOnPrivacy by rememberUpdatedState(onPrivacyClick)

    return remember(disclosure, terms, privacy, linkColor) {
        val style = TextLinkStyles(
            style = SpanStyle(
                color = linkColor,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            ),
        )
        AnnotatedString.Builder(disclosure).apply {
            link(disclosure, terms, style) { currentOnTerms() }
            link(disclosure, privacy, style) { currentOnPrivacy() }
        }.toAnnotatedString()
    }
}

private fun AnnotatedString.Builder.link(
    source: String,
    label: String,
    styles: TextLinkStyles,
    onClick: () -> Unit,
) {
    val start = source.indexOf(label).takeIf { it >= 0 } ?: return
    addLink(
        LinkAnnotation.Clickable(
            tag = label,
            styles = styles,
            linkInteractionListener = { onClick() },
        ),
        start,
        start + label.length,
    )
}
