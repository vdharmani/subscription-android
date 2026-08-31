package com.vdharmani.subscription.model

/**
 * What a restore actually resolved to.
 *
 * Restore is the trigger; the linkage decision is the result. Plain
 * `restore()` returns a [CustomerInfo] and throws the conflict away into a
 * generic failure, which leaves the caller unable to tell "nothing to restore"
 * from "this subscription belongs to somebody else's account" — two situations
 * that need very different UI.
 */
sealed interface RestoreOutcome {

    /**
     * The store had purchases and they belong to the signed-in app account —
     * either already linked to it, or unlinked and now linked to it. Both
     * paths end the same way: grant access.
     */
    data class Restored(val customerInfo: CustomerInfo) : RestoreOutcome

    /**
     * The store returned nothing for this store account. Show "No purchases
     * found"; it is not an error.
     */
    data class NothingToRestore(val customerInfo: CustomerInfo) : RestoreOutcome

    /**
     * The store subscription is already linked to a **different, live** app
     * account. Block, and show the conflict dialog — the user has to sign in
     * to that account, or switch store accounts to buy a separate one.
     *
     * The other account is deliberately not named: the store never exposes its
     * account email, and showing the app account email would leak it to
     * whoever is currently holding the device.
     */
    data class LinkedToAnotherAccount(val error: Throwable) : RestoreOutcome

    /** The restore itself failed — network, store outage, and so on. */
    data class Failed(val error: Throwable) : RestoreOutcome
}
