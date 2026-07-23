package com.vdharmani.subscription.model

/**
 * Metadata attached to the current billing identity — the provider dashboard
 * shows it alongside that subscriber's purchases, so a transaction can be
 * traced back to a real account.
 *
 * Reserved fields ([email], [displayName], [phoneNumber]) map onto the
 * provider's own well-known attributes; anything else goes in [custom].
 *
 * Semantics for every field, reserved or custom:
 *   - `null` — leave whatever the provider already has untouched.
 *   - `""` (blank) — clear the attribute on the provider.
 *
 * Attributes attach to the identity that is active when they are sent, so set
 * them *after* a successful `identify()`, not before.
 *
 * ```kotlin
 * sub.identify(user.id)
 * sub.setAttributes(SubscriberAttributes(email = user.email))
 * ```
 */
data class SubscriberAttributes(
    /** Purchase/contact email for the signed-in account. */
    val email: String? = null,

    /** Human-readable name shown next to the subscriber in the dashboard. */
    val displayName: String? = null,

    /** Contact phone number in E.164 form, e.g. `+14155552671`. */
    val phoneNumber: String? = null,

    /**
     * Provider-agnostic extras, keyed by your own attribute names (e.g.
     * `"plan_source"`). Same null/blank semantics as the reserved fields.
     */
    val custom: Map<String, String?> = emptyMap(),
) {
    /** True when there is nothing to send — callers can skip the round-trip. */
    val isEmpty: Boolean
        get() = email == null && displayName == null && phoneNumber == null && custom.isEmpty()
}
