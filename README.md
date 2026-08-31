# subscription-android

A small, opinionated Android library for in-app purchases and subscriptions.
One API for **INAPP + SUBS**, **Activity / Fragment / Compose**, swappable
billing provider underneath.

The default provider is [RevenueCat](https://www.revenuecat.com/), in an
opt-in module. The core knows nothing about RevenueCat — write your own
`BillingProvider` to swap it out (e.g. for native Play Billing) without
touching call sites.

---

## Highlights

- 🌍 **Live, localised prices.** `products(...)` gives you the store's own
  formatted price per region — no hardcoded numbers, no release to re-price.
- 🛒 **One API for INAPP + SUBS.** `purchase(productId, productType)` covers
  both one-shot purchases and auto-renewing subscriptions — pass
  `"productId:basePlanId"` to target one base plan of a multi-plan subscription.
- 🔄 **Restore + identify + logout** are first-class — no need to drop down
  to the SDK for the App Store / Play Store basics.
- ⬆️ **Plan switching.** `changeSubscription(new, old, mode)` does a real Play
  upgrade/downgrade with proration — not a second parallel purchase — and
  `ReplacementMode.forPlanSwitch(isUpgrade)` picks a mode the store accepts.
- 🏷️ **Subscriber attributes.** `setAttributes(...)` puts the purchase email
  (plus name, phone, or your own keys) next to the transaction in the
  provider dashboard.
- 📡 **Live customer state.** `observeCustomerInfo()` (Flow on the View side,
  `State` in Compose) updates on renewal, billing failure, restore, and
  identity switch.
- 🧱 **Swappable provider.** Core ships a `BillingProvider` SPI. Use
  `RevenueCatProvider` from the opt-in module or implement your own.
- 🚦 **Subscription-case handling.** Account conflicts, lifecycle states
  (grace / hold / paused / refunded), cross-platform and cross-store-account
  plan changes are detected and blocked *before* the purchase sheet opens —
  each resolving to a `title` + `message` you render however you like. No
  dialogs, buttons or layouts are shipped. See
  [Subscription cases](#subscription-cases).
- 🪶 **Three hosts.** Activity, Fragment, and Compose — all use the same
  underlying state machine.

---

## Install

**1.** Add JitPack in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**2.** Add the modules you need:

```kotlin
dependencies {
    implementation("com.github.vdharmani.subscription-android:subscription-core:1.6.0")
    // Pull this in iff you want RevenueCat under the hood.
    implementation("com.github.vdharmani.subscription-android:subscription-revenuecat:1.6.0")
}
```

If you write your own `BillingProvider` (against Play Billing or another SDK),
skip the second line.

---

## One-time setup

Register your provider in `Application.onCreate`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SubscriptionManager.initialize(
            RevenueCatProvider(this, BuildConfig.REVENUECAT_KEY),
        )
    }
}
```

After this line, every screen just constructs `SubscriptionClient(this)` or
calls `ComposeSubscription()` — no other code mentions the provider class.
Swapping to a different provider later is a single-line change here.

If the user is already signed in when the app starts, pass their id straight
to the provider to skip the anonymous-then-identify hop:

```kotlin
RevenueCatProvider(this, BuildConfig.REVENUECAT_KEY, appUserId = userId)
```

---

## Usage — Compose

```kotlin
@Composable
fun SubscribeScreen() {
    val sub = ComposeSubscription()
    val info by sub.customerInfo
    val isPremium = info?.hasEntitlement("premium") == true
    val scope = rememberCoroutineScope()

    Column {
        Text(if (isPremium) "You're subscribed" else "Upgrade to Premium")

        Button(onClick = {
            scope.launch {
                sub.purchase("premium_monthly", ProductType.SUBS)
                    .onSuccess { receipt -> viewModel.notifyServer(receipt) }
                    .onFailure { e ->
                        if (e !is PurchaseCancelledException) showError(e)
                    }
            }
        }) { Text(if (isPremium) "Manage" else "Subscribe — \$4.99/mo") }

        TextButton(onClick = { scope.launch { sub.restore() } }) {
            Text("Restore purchases")
        }
    }
}
```

`customerInfo` is a `State<CustomerInfo?>` backed by `observeCustomerInfo()`
through `collectAsStateWithLifecycle`. It re-emits whenever the underlying
provider notices a change (renewal, restore, identity switch).

---

## Usage — Activity

```kotlin
class SubscribeActivity : AppCompatActivity() {

    private val sub by lazy { SubscriptionClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscribe)

        // Live state — re-rendered on renewal, restore, etc.
        lifecycleScope.launch {
            sub.observeCustomerInfo().collect { info ->
                binding.subscribedTV.isVisible = info.hasEntitlement("premium")
            }
        }

        binding.subscribeBtn.setOnClickListener {
            sub.purchase("premium_monthly", ProductType.SUBS) { result ->
                result
                    .onSuccess { receipt -> viewModel.notifyServer(receipt) }
                    .onFailure { e ->
                        if (e !is PurchaseCancelledException) showError(e)
                    }
            }
        }

        binding.restoreBtn.setOnClickListener {
            sub.restore { result ->
                result.onSuccess { info -> viewModel.notifyServerRestore(info) }
            }
        }
    }
}
```

Both **suspend** and **callback** variants are available for every operation.
Use whichever fits your codebase.

---

## Usage — Fragment

```kotlin
class SubscribeFragment : Fragment(R.layout.fragment_subscribe) {

    private val sub by lazy { SubscriptionClient(this) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.subscribeBtn.setOnClickListener {
            lifecycleScope.launch {
                sub.purchase("premium_monthly", ProductType.SUBS)
                    .onSuccess { /* ... */ }
            }
        }
    }
}
```

The Fragment constructor registers everything against the Fragment's own
lifecycle, so callbacks don't outlive it.

---

## Live, region-correct prices

Never hardcode prices. `products(...)` returns what the store charges **this**
user, already converted and formatted for their currency and locale, so a
re-price in the console or a user in another country needs no app release:

```kotlin
val plans = sub.products(listOf("premium:monthly", "premium:yearly")).getOrDefault(emptyList())

plans.forEach { p ->
    Text(p.title)                       // "ProStyk Premium"
    Text(p.price.formatted)             // "₹1,600.00" / "$20.00" — render as-is
    Text("/${p.billingPeriod?.unit}")   // MONTH / YEAR — no string parsing
    p.freeTrialPeriod?.let { Text("${it.value} ${it.unit} free") }
}
```

- One entry **per base plan**: a subscription with monthly + yearly plans comes
  back as two products, each carrying the `"productId:basePlanId"` id that
  `purchase` wants — so the card the user taps and the plan they get can't drift
  apart.
- Ids the console doesn't know are simply missing from the list; check what came
  back rather than indexing blindly.
- `price.formatted` is for display; `amountMicros` + `currencyCode` are for
  maths and analytics. `pricePerMonthMicros` does the "₹X/mo billed yearly"
  arithmetic for you.
- Keep a hardcoded fallback for the offline/misconfigured case — this call needs
  the store to answer.

---

## Identification (sign-in / sign-out)

Tie purchases to your app's logged-in user so they survive a device wipe or
multi-device install:

```kotlin
// After your own sign-in completes
sub.identify(appUserId = userId)

// On sign-out
sub.logout()
```

Both return the new identity's `CustomerInfo`. Subscribers to `observeCustomerInfo()`
see the change automatically.

---

## Subscriber attributes (purchase email & co.)

`identify()` says *who* is buying; attributes say *how to reach them*. Send
them right after a successful `identify()` — they attach to whichever identity
is active at the time:

```kotlin
sub.identify(appUserId = user.id)
sub.setAttributes(
    SubscriberAttributes(
        email = user.email,          // shown against the purchase in the dashboard
        displayName = user.fullName,
        custom = mapOf("plan_source" to "trial"),
    ),
)
```

Per field: `null` leaves the existing value untouched, `""` clears it. Anything
you don't pass is left alone, so it's safe to call with just the one field you
know. Attributes are metadata, not entitlement state — providers upload them in
the background, so `Result.success` means *accepted*, not *synced*.

A provider whose SDK has no attribute concept inherits the interface default
(a no-op success), so this never breaks a custom `BillingProvider`.

---

## Upgrade / downgrade an active subscription

Switching plans is **not** a second `purchase()` — that leaves the user paying
for both. Play needs the product being replaced plus a replacement mode, which
is what `changeSubscription` sends:

```kotlin
// Monthly → Yearly: charge the year now, carry the unused monthly time over
sub.changeSubscription(
    productId = "premium:yearly",
    oldProductId = "premium:monthly",
    replacementMode = ReplacementMode.forPlanSwitch(isUpgrade = true),
)

// Yearly → Monthly: nothing to pay until the paid-for year runs out
sub.changeSubscription(
    productId = "premium:monthly",
    oldProductId = "premium:yearly",
    replacementMode = ReplacementMode.forPlanSwitch(isUpgrade = false),
)
```

`oldProductId` is the plan the user is on right now, and the store is what
knows that — `Entitlement.id` carries the base plan, so read it from there
rather than from whatever plan your own records last wrote down:

```kotlin
val active = sub.customerInfo().getOrNull()
    ?.activeEntitlements
    ?.firstOrNull { it.identifier == "premium" }

active?.let {
    sub.changeSubscription(
        productId = "premium:yearly",
        oldProductId = it.id,          // "premium:monthly"
        replacementMode = ReplacementMode.forPlanSwitch(isUpgrade = true),
    )
}
```

The example above is the common Play setup: **one** subscription product with
two base plans. Separate products work the same way — pass their ids instead,
and `forPlanSwitch(isUpgrade = false, sameSubscription = false)` if you want a
downgrade deferred to the end of the period.

| Mode | Charged today | Switch takes effect | Same product? |
|---|---|---|---|
| `CHARGE_FULL_PRICE` *(default)* | Full new price | Immediately, unused time carried over as credit | ✅ |
| `WITHOUT_PRORATION` | Nothing | Immediately, new price at next renewal, billing date unchanged | ✅ |
| `CHARGE_PRORATED_PRICE` | Prorated difference | Immediately, billing date unchanged | ❌ |
| `WITH_TIME_PRORATION` | Nothing | Immediately, next billing date pushed out | ❌ |
| `DEFERRED` | Nothing | At the end of the current period | ❌ |

**Same product?** is what Play allows when both plans are base plans of *one*
subscription — the setup in the example. There, only `CHARGE_FULL_PRICE` and
`WITHOUT_PRORATION` are legal; the others make Play fail the purchase and show
the user an error, so `RevenueCatProvider` maps them onto the closest legal mode
(and logs a warning) instead of letting the flow die. `CHARGE_PRORATED_PRICE`
carries a second restriction even across separate products: Play only accepts it
when the price **per unit of time goes up**, which a discounted annual plan does
not do. Use `ReplacementMode.forPlanSwitch(...)` and you don't have to track any
of this.

`DEFERRED` resolves successfully *before* the new plan starts — the receipt
describes the queued change, and entitlements only move at renewal, so don't
treat that success as "the user is on the new plan now".

Both hosts have it (`SubscriptionClient` suspend + callback, and the Compose
manager). A provider that can't switch plans inherits the interface default,
which fails with `SubscriptionChangeUnsupportedException`.

---

## Subscription cases

A store subscription is owned by the **Google Play account**, not by your app
account. Almost every awkward case falls out of that one fact, and the library
handles them as follows.

| # | Case | What the library does |
|---|---|---|
| 1 | Store sub linked to another **live** app account | `SubscriptionAlreadyLinkedException` → conflict dialog copy |
| 1A | Different store account on same device | Nothing — a separate, legitimate subscription |
| 2 | Store sub linked to no app account | Restores and links normally (`RestoreOutcome.Restored`) |
| 3 | Account deletion with an active subscription | `accountDeletion(...)` warning copy + `openManageSubscription()`; never blocks |
| 4 | Store sub active, linked app account deleted | Same as case 2 — your backend soft-deletes the linkage row |
| 5 | Plan change after store-account switch | `PlanChangeEligibility.Blocked(STORE_ACCOUNT_MISMATCH)` — needs a provider that implements `ownedByCurrentStoreAccount` |
| 6 | Grace / hold / paused / refunded | `Entitlement.status` + `grantsAccess`, with per-state copy |
| 7 | Cross-platform entitlement | `PlanChangeEligibility.Blocked(CROSS_PLATFORM)` — access still granted |
| 8 | Normal plan change, same store account | `changeSubscription(...)` + `ReplacementMode.forPlanSwitch(...)` |
| 9 | Resubscribe after expiry | Plain `purchase(...)`; check `hasAccess()` first so you never double-bill |

### Messages

Every resolver returns a `SubscriptionMessage(title, body)`, or `null` when
there is nothing to say. **That is the whole UI surface.** The library ships no
buttons, no dialogs, no layouts and no styling — it tells you what happened and
what to say about it; you decide how it looks.

```kotlin
val message = SubscriptionMessages.forError(context, error)
// null == user cancelled the sheet. Say nothing; it is not an error.
message?.let { showDialog(it.title, it.body) }
```

| Resolver | Answers |
|---|---|
| `forError(context, throwable)` | a failed purchase / restore / plan change |
| `forEntitlement(context, entitlement)` | a suspended subscription (grace, hold, paused, refunded) |
| `forRestore(context, outcome)` | a finished restore |
| `planChangeBlocked(context, blocked)` | why an upgrade isn't offered (title-less, inline) |
| `accountDeletion(context, customerInfo)` | the pre-deletion billing warning |
| `disclosure(context)` | the paywall auto-renewal text |

Copy lives in `res/values/strings.xml` in `subscription-core`. **To change the
wording, redeclare the same string name in your app** — Android's resource
merger prefers yours. Translate by adding your own `values-<locale>`.

### Lifecycle states (case 6)

Gate access on **state**, never on "an entitlement exists":

```kotlin
val entitlement = customerInfo.entitlement("premium")
if (customerInfo.hasAccess("premium")) {
    unlockPremium()
}
// Explain the suspended states — hold, paused and refunded are three
// different situations and deliberately do not share a title.
entitlement?.let { SubscriptionMessages.forEntitlement(context, it) }
    ?.let { showBanner(it.title, it.body) }
```

| `SubscriptionStatus` | Access | Notes |
|---|---|---|
| `ACTIVE` | ✅ | |
| `CANCELLED` | ✅ | Auto-renew off, period not over. **Not** expired |
| `IN_GRACE_PERIOD` | ✅ | Payment retrying; renewal date does not move on recovery |
| `ON_HOLD` | ❌ | Recoverable; Play's hold runs up to 60 days minus the grace period |
| `PAUSED` | ❌ | Android only; resumes automatically on `autoResumeAtSeconds` |
| `EXPIRED` | ❌ | |
| `REFUNDED` | ❌ | Final — pull access at once, do not run out the period |

Suspended entitlements are **not** in `activeEntitlements`; read
`allEntitlements`, or `entitlement(id)` / `statusOf(id)`, or a failed payment
looks identical to a user who never subscribed.

> Play applies a minimum **one-day silent grace period** even when you
> configure zero, during which a failed payment still reports as active. Don't
> build logic that assumes a failure surfaces immediately.

### Plan changes (cases 5, 7, 8)

Check before you offer the button:

```kotlin
when (val eligibility = sub.planChangeEligibility(currentProductId)) {
    is PlanChangeEligibility.Allowed ->
        showUpgradeButton()
    is PlanChangeEligibility.Blocked ->
        showNote(SubscriptionMessages.planChangeBlocked(context, eligibility))
}
```

`changeSubscription` runs the same check itself unless you set
`Config(guardPlanChanges = false)`. It is worth the round trip: on Play a
switch against a token the current account doesn't own fails with a developer
error, and on the App Store it fails *silently* — StoreKit only applies an
upgrade when the same Apple ID owns the old subscription, so otherwise the
user quietly ends up paying two live subscriptions. Access is never revoked by
a block; only the switch is refused.

### Restore (cases 1, 2, 4)

`restorePurchases()` classifies the result instead of collapsing the conflict
into a generic failure:

```kotlin
when (val outcome = sub.restorePurchases()) {
    is RestoreOutcome.Restored -> grantAccess(outcome.customerInfo)
    is RestoreOutcome.NothingToRestore,
    is RestoreOutcome.LinkedToAnotherAccount,
    is RestoreOutcome.Failed ->
        SubscriptionMessages.forRestore(context, outcome)?.let { showDialog(it) }
}
```

Run it on launch, on login, **and on app foreground** — the store account can
change while your app is backgrounded, which is exactly what leaves a stale
upgrade button on screen.

### Account deletion (case 3)

Warn, then let them through. Blocking deletion behind an active subscription
is a realistic App Store rejection, and the subscription is not yours to
cancel:

```kotlin
// null == nothing is set to auto-renew, so a billing warning would be a lie.
val warning = SubscriptionMessages.accountDeletion(context, customerInfo)
if (warning == null) {
    showPlainDeleteConfirmation()
} else {
    AlertDialog.Builder(context)
        .setTitle(warning.title)
        .setMessage(warning.body)
        .setNegativeButton(R.string.keep_account) { _, _ ->
            sub.openManageSubscription(productId, customerInfo)
        }
        .setPositiveButton(R.string.delete_anyway) { _, _ -> deleteAccount() }
        .show()
}
```

Button labels are yours. One caution worth keeping: don't label the dismiss
button "Cancel" — the body already uses "cancel" to mean cancelling the
subscription, so the same word on a button that does the opposite is a misread
waiting to happen.

### Paywall disclosure

Both stores want the auto-renewal terms before the user confirms. You get the
sentence plus the two substrings that have to be links; spanning and styling
them is yours:

```kotlin
val text = SubscriptionMessages.disclosure(context)
val terms = SubscriptionMessages.termsLabel(context)     // where TERMS_URL goes
val privacy = SubscriptionMessages.privacyLabel(context) // where PRIVACY_URL goes
```

Read the link targets from `termsLabel` / `privacyLabel` rather than searching
the sentence for the literal words "Terms of Use" — that keeps the links
landing on the right span once the string is translated.

---

## Configuration

`SubscriptionClient.Config`:

| Field | Default | Purpose |
|---|---|---|
| `requirePlayStoreInstaller` | `false` | When `true`, [purchase] is blocked on debuggable builds and on builds whose installer is anything other than the Play Store (`com.android.vending`). Match the safety check from the reference impl. Opt-in. |
| `guardPlanChanges` | `true` | Check that a plan switch is actually possible (right store, right store account, subscription live) before opening the purchase sheet; fails with `PlanChangeUnavailableException` instead. Costs one customer-info lookup and fails **open** if that lookup fails. |

---

## Writing your own `BillingProvider`

If you want native Play Billing or a different SDK, implement the SPI:

```kotlin
class MyPlayBillingProvider(context: Context) : BillingProvider {
    override suspend fun purchase(activity: Activity, productId: String, productType: ProductType): Result<Receipt> { /* ... */ }
    // Optional — defaults to ProductQueryUnsupportedException.
    override suspend fun products(productIds: List<String>, productType: ProductType): Result<List<Product>> { /* ... */ }
    // Optional — defaults to SubscriptionChangeUnsupportedException.
    override suspend fun changeSubscription(
        activity: Activity,
        productId: String,
        oldProductId: String,
        replacementMode: ReplacementMode,
    ): Result<Receipt> { /* ... */ }
    override suspend fun restore(): Result<CustomerInfo> { /* ... */ }
    override suspend fun customerInfo(): Result<CustomerInfo> { /* ... */ }
    override suspend fun identify(appUserId: String): Result<CustomerInfo> { /* ... */ }
    override suspend fun logout(): Result<CustomerInfo> { /* ... */ }
    override fun observeCustomerInfo(): Flow<CustomerInfo> { /* ... */ }

    // Optional — defaults to a no-op success. Override only if your SDK
    // supports subscriber metadata.
    override suspend fun setAttributes(attributes: SubscriberAttributes): Result<Unit> { /* ... */ }

    // Optional — defaults to Store.PLAY_STORE. The store you actually talk to,
    // used to tell "we can change this plan" from "this plan is billed
    // elsewhere and is read-only here".
    override val nativeStore: Store get() = Store.PLAY_STORE

    // Optional — defaults to Result.success(null), meaning "can't tell".
    // Return false only when you have positively determined the signed-in
    // store account does not own the purchase behind this entitlement, e.g. by
    // matching entitlement.storeTransactionId against the purchase tokens
    // queryPurchasesAsync currently returns.
    override suspend fun ownedByCurrentStoreAccount(
        entitlement: Entitlement,
    ): Result<Boolean?> { /* ... */ }
}
```

Map your SDK's errors onto the typed `BillingException` subclasses — in
particular, "this receipt already belongs to another subscriber" must become
`SubscriptionAlreadyLinkedException`, not a generic `AlreadyOwnedException`, or
the account-conflict copy never fires.

Then register it instead of `RevenueCatProvider`. No other code changes.

---

## What the library does **not** do

- It does **not** call your server. The library hands you a [Receipt]; your
  app posts it wherever it needs to go (your verify endpoint, analytics, etc.).
- It does **not** compute expiry for time-limited INAPP "passes". If you sell
  a 7-day pass as an `INAPP` product, your server (or your app) does the
  `purchasedAtSeconds + 7 * 24 * 3600` math — the library just hands you the
  receipt.
- It does **not** ship a paywall UI, dialogs, buttons or any View/Composable.
  `SubscriptionMessages` resolves a `title` + `message`; rendering, button
  labels and styling are yours.
- It does **not** own the app-account ↔ store-account linkage. That record
  lives on your backend. The library reports what the store says and gives you
  the typed outcome to act on; linking, soft-deleting, and resolving
  `linkedPurchaseToken` are server-side work.
- It does **not** detect a store-account switch on its own with
  `RevenueCatProvider`. RevenueCat never exposes the underlying Play purchase
  token, so `ownedByCurrentStoreAccount` stays at "can't tell" and Case 5 is
  caught by Play failing the switch rather than pre-emptively. Implement that
  hook in your own provider (or a `RevenueCatProvider` subclass) if you need
  the button disabled up front.

---

## License

MIT — see [`LICENSE`](LICENSE).
