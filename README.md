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

- 🛒 **One API for INAPP + SUBS.** `purchase(productId, productType)` covers
  both one-shot purchases and auto-renewing subscriptions.
- 🔄 **Restore + identify + logout** are first-class — no need to drop down
  to the SDK for the App Store / Play Store basics.
- ⬆️ **Plan switching.** `changeSubscription(new, old, mode)` does a real Play
  upgrade/downgrade with proration — not a second parallel purchase.
- 🏷️ **Subscriber attributes.** `setAttributes(...)` puts the purchase email
  (plus name, phone, or your own keys) next to the transaction in the
  provider dashboard.
- 📡 **Live customer state.** `observeCustomerInfo()` (Flow on the View side,
  `State` in Compose) updates on renewal, billing failure, restore, and
  identity switch.
- 🧱 **Swappable provider.** Core ships a `BillingProvider` SPI. Use
  `RevenueCatProvider` from the opt-in module or implement your own.
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
    implementation("com.github.vdharmani.subscription-android:subscription-core:1.3.0")
    // Pull this in iff you want RevenueCat under the hood.
    implementation("com.github.vdharmani.subscription-android:subscription-revenuecat:1.3.0")
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
// Monthly → Annual: charge the prorated difference now, keep the billing date
sub.changeSubscription(
    productId = "premium_annual",
    oldProductId = "premium_monthly",
    replacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
)

// Annual → Monthly: let the paid-for year run out first
sub.changeSubscription(
    productId = "premium_monthly",
    oldProductId = "premium_annual",
    replacementMode = ReplacementMode.DEFERRED,
)
```

| Mode | Charged today | Switch takes effect |
|---|---|---|
| `CHARGE_PRORATED_PRICE` *(default)* | Prorated difference | Immediately, billing date unchanged |
| `WITH_TIME_PRORATION` | Nothing | Immediately, next billing date pushed out |
| `WITHOUT_PRORATION` | Nothing | Immediately, unused time forfeited |
| `CHARGE_FULL_PRICE` | Full new price | Immediately, period restarts |
| `DEFERRED` | Nothing | At the end of the current period |

`DEFERRED` resolves successfully *before* the new plan starts — the receipt
describes the queued change, and entitlements only move at renewal, so don't
treat that success as "the user is on the new plan now".

Both hosts have it (`SubscriptionClient` suspend + callback, and the Compose
manager). A provider that can't switch plans inherits the interface default,
which fails with `SubscriptionChangeUnsupportedException`.

---

## Configuration

`SubscriptionClient.Config`:

| Field | Default | Purpose |
|---|---|---|
| `requirePlayStoreInstaller` | `false` | When `true`, [purchase] is blocked on debuggable builds and on builds whose installer is anything other than the Play Store (`com.android.vending`). Match the safety check from the reference impl. Opt-in. |

---

## Writing your own `BillingProvider`

If you want native Play Billing or a different SDK, implement the SPI:

```kotlin
class MyPlayBillingProvider(context: Context) : BillingProvider {
    override suspend fun purchase(activity: Activity, productId: String, productType: ProductType): Result<Receipt> { /* ... */ }
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
}
```

Then register it instead of `RevenueCatProvider`. No other code changes.

---

## What the library does **not** do

- It does **not** call your server. The library hands you a [Receipt]; your
  app posts it wherever it needs to go (your verify endpoint, analytics, etc.).
- It does **not** compute expiry for time-limited INAPP "passes". If you sell
  a 7-day pass as an `INAPP` product, your server (or your app) does the
  `purchasedAtSeconds + 7 * 24 * 3600` math — the library just hands you the
  receipt.
- It does **not** ship a paywall UI. Build your own from `customerInfo` +
  `purchase()` — exactly the shape your app needs.

---

## License

MIT — see [`LICENSE`](LICENSE).
