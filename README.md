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
    implementation("com.github.vdharmani.subscription-android:subscription-core:1.1.1")
    // Pull this in iff you want RevenueCat under the hood.
    implementation("com.github.vdharmani.subscription-android:subscription-revenuecat:1.1.1")
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
    override suspend fun restore(): Result<CustomerInfo> { /* ... */ }
    override suspend fun customerInfo(): Result<CustomerInfo> { /* ... */ }
    override suspend fun identify(appUserId: String): Result<CustomerInfo> { /* ... */ }
    override suspend fun logout(): Result<CustomerInfo> { /* ... */ }
    override fun observeCustomerInfo(): Flow<CustomerInfo> { /* ... */ }
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
