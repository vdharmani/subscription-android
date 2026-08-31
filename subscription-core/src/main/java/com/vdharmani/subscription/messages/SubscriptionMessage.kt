package com.vdharmani.subscription.messages

/**
 * A resolved, ready-to-show message.
 *
 * [title] is null for every message the library resolves. That is deliberate:
 * AppSpec defines **one string per case**, so inventing a title here would put
 * unspecified copy in front of users and break the "match word for word" rule.
 * Where your design needs a dialog heading, supply your own.
 */
data class SubscriptionMessage(
    val title: String?,
    val body: String,
    /** Which container AppSpec says this message belongs in. */
    val display: Display,
    /** AppSpec case id this string comes from, e.g. `"PUR-8A70"`. */
    val caseId: String,
) {
    /**
     * The container a message is shown in — AppSpec's "Shown as" column.
     *
     * It travels with the message because the choice is not cosmetic and not
     * per-screen: AppSpec forbids specific pairings outright. A store conflict
     * or a destructive confirmation must never be a toast, and an ongoing
     * state such as offline or a payment issue must be a banner rather than a
     * toast that fires again on every retry.
     */
    enum class Display {
        /** Under the field or next to the item it describes. */
        INLINE,

        /**
         * Transient outcome. On Android use a Material `Snackbar` —
         * `android.widget.Toast` is rate-limited on Android 11+ and dropped
         * from the background.
         */
        TOAST,

        /** Ongoing state. Stays until the state clears. */
        BANNER,

        /** Needs a decision, or must be read before continuing. */
        DIALOG,

        /** Belongs on the paywall, as the reason the user is seeing it. */
        PAYWALL,

        /** Fixed screen copy, such as the purchase disclosure. */
        STATIC,
    }
}
