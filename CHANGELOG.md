# Changelog

## 2.0.0

Message layer rebuilt against **AppSpec** ("App Specification — Messages & Test
Cases"), which is the canonical catalogue and requires *"one string per case …
match word for word"*. An audit against it found only 4 of ~34 in-scope
messages matching, so the catalogue was replaced rather than patched.

### Fixed

- **Pending payments were reported as declines.** `PaymentPendingError` mapped
  to `PaymentDeclinedException`, so a user whose UPI or slow-card payment was
  still in flight was told their payment had been refused. Pending is now its
  own type and its own message (PUR-8A70); Ask to Buy approval (TRL-1C28)
  takes the same path.
- **Cancelled and expired subscriptions produced no message.** AppSpec
  specifies one for each (STA-FAA1, STA-F01C); a cancelled *trial* gets
  TRL-DDA5 rather than the paid wording.
- **A blocked plan change could fail silently.** Tapping Upgrade on a lapsed
  subscription resolved to no message at all. Only a dismissed purchase sheet
  is silent now.
- **`minSdk` was declared as 21** while `androidx.activity`, RevenueCat and
  Play Billing all require 23. The library AAR built anyway, but a consumer at
  21 hit a manifest merger failure. Now 23 — nothing below it could ever have
  worked.
- **Account conflicts were indistinguishable from double-buys.**
  `ReceiptAlreadyInUseError` mapped to the same exception as "you already own
  this", so the Case 1 dialog could never fire.
- **Suspended entitlements were invisible.** `CustomerInfo` exposed only
  RevenueCat's *active* map, in which account hold, paused and refunded do not
  appear — making a failed payment look identical to a user who never
  subscribed.

### Added

- **Case 5 detection.** `RevenueCatProvider` implements
  `ownedByCurrentStoreAccount` by asking Play what the signed-in account owns,
  so a plan change after a store-account switch is blocked *before* the
  purchase sheet opens. Any uncertainty — connection failure, timeout, pending
  purchase — allows the change; only a definite negative blocks.
- `SubscriptionStatus` with `grantsAccess`: the access decision for grace,
  hold, paused, refunded, cancelled and expired, encoded once.
- `CustomerInfo.allEntitlements`, `hasAccess`, `statusOf`,
  `hasRenewingSubscription`.
- Typed outcomes: `PaymentPendingException`, `ReceiptValidationException`,
  `SecureConnectionException`, `TrialNotEligibleException`,
  `OfferUnavailableException`, `SubscriptionAlreadyLinkedException`,
  `PlanChangeUnavailableException`, `PlayStoreInstallRequiredException`.
- `RestoreOutcome`, `PlanChangeEligibility`, `Store`, `PeriodType`,
  `OwnershipType`, `ManageSubscription`.
- `AppSpecConformanceTest`, which fails the build when a shipped string stops
  matching AppSpec.

### Changed — breaking

- `SubscriptionMessage` is `body` + `display` + `caseId`. **There is no
  `title`**: AppSpec defines one string per case, so a dialog heading is the
  app's to supply. Button labels and styled/spanned text were removed for the
  same reason — the library reports what happened, the app decides how it
  looks.
- `SubscriptionMessages.forError` returns `null` **only** for a cancelled
  sheet. Every other failure resolves to a message.
- `SubscriptionAlreadyLinkedException` extends `AlreadyOwnedException`, so
  existing `is AlreadyOwnedException` branches keep matching — but **match the
  specific type first** or the conflict shows the wrong copy.
- `changeSubscription` now runs an eligibility check before opening the sheet
  (`Config.guardPlanChanges`, default on). It fails **open** if the lookup
  fails.
- `minSdk` 21 → 23.

### Migration from 1.x

1. Drop any use of `message.title`.
2. Supply your own button labels; AppSpec's are *Keep Account* / *Delete
   Anyway*. Do not label the dismiss button "Cancel" — the body already uses
   "cancel" to mean cancelling the subscription.
3. Add a `PaymentPendingException` branch. If you match
   `PaymentDeclinedException` today, pending purchases used to land there and
   were shown as failures.
4. Gate features on `hasAccess(id)` / `grantsAccess`, not on the presence of
   an entitlement.
5. Raise `minSdk` to 23 if you are below it.

### Known gaps

- Case 5 has not been exercised against an account that genuinely **owns** a
  subscription for the package; that needs a published build and a real
  purchase.
- Trial/offer eligibility (TRL-B9A4), price-change consent (TRL-C284) and
  acknowledgement (PUR-ACE9) ship as strings, not mechanisms. RevenueCat
  acknowledges automatically unless you enable Observer Mode or set
  `finishTransactions = false` — if you do either, you must acknowledge
  yourself or Play refunds the purchase after 3 days.
- No entitlement-caching policy (CAC-4D3C, CAC-27DB, SIN-EC75). `offline()`
  gives you the banner, not the rule.
- `ReplacementMode.forPlanSwitch` cannot know a target base plan is **prepaid**,
  where Play accepts only `CHARGE_FULL_PRICE`. Pass it explicitly there.

---

## 1.6.0 and earlier

See the git history. Anything before 2.0.0 predates the AppSpec catalogue and
carries the pending-payment defect above.
