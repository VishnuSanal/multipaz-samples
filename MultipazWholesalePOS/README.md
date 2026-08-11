# Multipaz Wholesale POS

A point-of-sale terminal that accepts payments from a customer's **Digital Payment Credential (DPC)
**
presented over **ISO 18013-5 proximity** (NFC tap or QR + BLE),
using [Multipaz](https://github.com/openwallet-foundation/multipaz).

The terminal reads the customer's DPC, has the customer's device **cryptographically authorize the
exact amount** (SCA payment `transaction_data`), and **settles the payment on a ledger** — moving
funds from the customer's account to the merchant's account on a Multipaz **records server** (the
"System of Record", or SoR). The POS app itself holds **no signing key**: it proves it is a genuine
terminal build via **device attestation** to a small **terminal backend**, which holds the payment
key.

---

## What happens when a customer pays

```
┌──────────┐   QR / NFC    ┌───────────────┐  device attestation   ┌───────────────────┐   pp-leaf   ┌────────────────────┐
│  Holder  │  proximity    │   POS app     │  (Android key attest, │  Terminal backend │  signature  │                    │
│ (wallet, ├──────────────▶│ (reader + UI) ├──────────────────────▶│      :8110        ├────────────▶│  Records server    │
│  a DPC)  │  presentment  │               │  RpcAuthorizedDevice  │  holds pp-leaf,   │  Payment    │  verifies mdoc +   │
└──────────┘               └───────────────┘  Client               │  checks app sig   │  Processor  │  moves funds       │
                                                                   └───────────────────┘   RPC       └────────────────────┘
```

1. **Reserve** — the app calls the terminal backend's `createTransaction(amount)`, which forwards to
   the SoR and gets back a server-minted `transactionId`.
2. **Read + bind** — the app reads the DPC over proximity and attaches an SCA `transaction_data`
   object carrying that `transactionId` + amount/currency/payee. The customer's wallet *
   *device-signs
   the hash** of it. The app verifies the signature locally and **rejects the read if the device
   didn't authorize this exact amount**.
3. **Settle** — the app sends the device-signed presentment to the terminal backend's
   `commitTransaction`, which forwards it to the SoR. The SoR re-verifies the mdoc issuer + device
   signatures, the amount binding, the issuer trust chain, and then **debits the payer / credits the
   payee** on its ledger.
4. **Result** — on success the screen shows the ledger confirmation id as the transaction id and
   "AUTHORIZED BY CARD". Any failure (no settler, unbound amount, untrusted issuer, unknown account,
   RPC error) shows a **Declined** modal the cashier can retry.

The customer's DPC is an ISO 18013-5 mdoc with docType/namespace `org.multipaz.payment.sca.1`, whose
`payment_instrument_id` claim is the customer's ledger account number.

---

## Architecture & the three trust gates

Three independent checks must all pass for a payment to settle:

| Gate                       | Question                                                               | Where it's enforced                                                       | How it's satisfied here                                                                                                                                                                  |
|----------------------------|------------------------------------------------------------------------|---------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1. App genuineness**     | Is this a real POS terminal build?                                     | Terminal backend, via `RpcAuthInspectorAssertion` + `client_requirements` | The app does **Android key attestation**; the backend checks the attestation's app **signing-cert digest + package**                                                                     |
| **2. Terminal → SoR auth** | Is this terminal allowed to move money?                                | SoR, via `RpcAuthInspectorSignature` against its `PAYMENT_PROCESSOR` root | The terminal backend signs with **`pp-leaf`**, whose cert chains to **`pp-root`**, which the SoR trusts (`root_identities.payment_processor`)                                            |
| **3. Credential trust**    | Is the DPC from a trusted issuer, and did the holder sign this amount? | SoR, in `commitTransaction`                                               | Issuer chain verified against the SoR's `TrustManager`; the device-signed `transaction_data` amount must equal the reserved transaction; proximity `verifyNonce` accepts the presentment |

Gate 1 is the point of this design: the payment key (`pp-leaf`) lives **only** on the terminal
backend, never in the distributed APK. The app authenticates by *being a genuine build*, checked
cryptographically via key attestation.

---

## Repository layout

```
MultipazWholesalePOS/
├── androidApp/       Android entry point (MainActivity wires up the app + settler)
├── shared/           KMP shared code (Compose UI, the proximity reader, the settlement client)
│   └── src/
│       ├── commonMain/    App(), CheckoutScreen, CardVerification, PaymentCardReader,
│       │                   PaymentSettler, RpcPaymentSettler (the shared device-attestation client)
│       ├── androidMain/    AndroidPaymentSettler (OkHttp + AndroidKeystoreSecureArea)
│       └── iosMain/        IosPaymentSettler (Darwin + SoftwareSecureArea) + MainViewController
├── iosApp/           iOS entry point (see “iOS status” below)
└── terminalBackend/  JVM server: the merchant's terminal backend (device-attested PaymentProcessor proxy)
```

Key files:

- `shared/…/payment/PaymentCardReader.kt` — the ISO 18013-5 reader; attaches `transaction_data`,
  verifies the device-signed amount, packages the `Iso18013PresentmentRecord`.
- `shared/…/payment/PaymentSettler.kt` — the settlement interface (`createTransaction` / `commit`).
- `shared/…/commonMain/…/RpcPaymentSettler.kt` — the shared client: device-attestation handshake
  (`RpcAuthorizedDeviceClient`) + the two `PaymentProcessor` RPCs. Platforms inject only an HTTP
  engine + a `SecureArea` (see `AndroidPaymentSettler` / `IosPaymentSettler`).
- `terminalBackend/…/TerminalPaymentProcessor.kt` — implements `PaymentProcessor` with device
  attestation, forwards to the SoR with `pp-leaf`.
- `terminalBackend/…/resources/resources/default_configuration.json` — the backend's baked-in config
  (port, `client_requirements`, `records_server_url`, and `pp-leaf`).

---

## Prerequisites

1. **A patched Multipaz SDK, published to your local Maven.** Two source changes are required (see
   [Required SDK changes](#required-sdk-changes)), then:
   ```
   cd /path/to/multipaz
   ./gradlew publishToMavenLocal -Psnapshot=true          # publishes org.multipaz:*:0.101.0-SNAPSHOT
   ```
   The POS resolves `org.multipaz` **only from mavenLocal** (see `settings.gradle.kts`), so it
   always picks up your patched build.
2. **A records server (SoR)** — either the standalone `multipaz-records-server` or the Docker Utopia
   stack (both covered below). It must trust `pp-root` and the DPC's issuer, and have the
   payer/payee accounts.
3. **A holder** — the Multipaz TestApp (built from the same SDK) with a DPC (
   `org.multipaz.payment.sca.1`)
   provisioned. Its `payment_instrument_id` must equal a seeded payer account.
4. **An Android device or emulator**, and `adb`.

---

## Build & run — end to end

The terminal backend and app are the same for both SoR options; only the SoR differs.

### Step 1 — start a records server (SoR)

**Docker Utopia stack:**

Use [this branch](https://github.com/VishnuSanal/multipaz-utopia/tree/pos)

```
cd /path/to/multipaz-utopia
./gradlew :deployment:buildDockerImage
docker run --rm -p 8100:8100 multipaz-utopia/server-bundle:latest
# registry (SoR) RPC is now at http://localhost:8100/registry/rpc
```

The image already carries the committed Utopia edits: `pp-root` in `registry.conf`, the merchant
account `20000001` in `records.json`, and the utopia.multipaz.org issuer in the registry trust
manager. Accounts are auto-seeded on startup.

> ⚠️ The image must contain the `verifyNonce` patch. Utopia resolves `0.101.0-SNAPSHOT`; make sure
> it
> resolves your **patched** local build (mavenLocal), not the unpatched remote snapshot — see
> [Troubleshooting](#troubleshooting).

### Step 2 — start the terminal backend

```
./gradlew :terminalBackend:run
```

No arguments needed — everything (including `pp-leaf`) is in its baked `default_configuration.json`.
It listens on **:8110**.

### Step 3 — build, install, and bridge the port

```
./gradlew :androidApp:installDebug
adb reverse tcp:8110 tcp:8110      # device localhost:8110 → host terminal backend
```

### Step 4 — take a payment

In the app: enter an amount → **Checkout** → hold the holder's DPC to the phone (NFC) or scan its
share QR. On success you'll see "SETTLED / AUTHORIZED BY CARD" with the ledger confirmation id.

### Step 5 — verify funds moved

You can inspect the transaction logs on http://localhost:8100/registry/account.html?id=20000001

---

## Configuration reference

### Terminal backend (`terminalBackend/…/default_configuration.json`)

```jsonc
{
  "server_port": 8110,
  "database_engine": "ephemeral",                       // no db file; re-registers clients each run
  "admin_password": "multipaz",
  "records_server_url": "http://localhost:8101",        // the SoR; override via -param for Docker
  "client_requirements": {                              // GATE 1 — who may call this backend
    "android": {
      "gms_attestation": false,                         // no Play Integrity (dev)
      "verified_boot_green": false,
      "keystore_security_level": "software",            // accept emulator/software-level attestation
      "app_signature_certificate_digests": ["<APK signing SHA-256>"],
      "app_packages": ["org.multipaz.pos"]
    }
  },
  "server_identities": { "payment_processor": { …pp-leaf JWK… } }   // GATE 2 — the terminal's key
}
```

Get your APK's signing digest (debug builds use `~/.android/debug.keystore`):

```
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```

### App (`AndroidPaymentSettler`)

- `DEFAULT_TERMINAL_URL = http://localhost:8110/rpc` — the terminal backend (via `adb reverse`).
- `DEFAULT_PAYEE_ACCOUNT = 20000001` — the merchant account sent in `createTransaction`.

### Keys (`pp-root` / `pp-leaf`)

A single EC P-256 keypair chain: **`pp-root`** is a self-signed CA (the SoR's `PAYMENT_PROCESSOR`
trust root), **`pp-leaf`** is a cert signed by it (the terminal's identity). `pp-leaf` lives in the
terminal backend config; `pp-root` lives in the SoR config (`root_identities.payment_processor`).

---

## Security model & dev-vs-production

This sample runs at a **dev tier**. What's real vs. what a production terminal would change:

| Aspect              | Here (dev)                                                                            | Production                                                   |
|---------------------|---------------------------------------------------------------------------------------|--------------------------------------------------------------|
| App verification    | Android key attestation, **software** keystore level, no Play Integrity               | `gms_attestation: true` (Play Integrity) + hardware keystore |
| Payment key custody | `pp-leaf` committed in the backend config (like the Wallet's dev `server_identities`) | key in a secret manager / HSM, never in git                  |
| SoR trust root      | shared self-signed `pp-root`                                                          | terminal **enrolls** with the SoR's CA for a real cert       |
| Cleartext HTTP      | allowed (`usesCleartextTraffic`, `http://…`)                                          | TLS everywhere                                               |
| Issuer trust        | utopia.multipaz.org added to the SoR TrustManager                                     | the real payment-network trust anchors                       |

What is *already* production-shaped: the app holds no key and is verified by attestation; the
payment
key is server-side; settlement is card-bound and authoritative on the SoR; the mdoc issuer + device
signatures are verified.

---

## Required SDK changes

The POS depends on two changes in the Multipaz SDK / Utopia (published to mavenLocal / baked into
the SoR):

Use [this branch](https://github.com/VishnuSanal/multipaz-identity-credential/tree/pos)

1. **Proximity `verifyNonce`** — `Iso18013PresentmentRecord.verifyNonce` must accept a proximity
   presentment (return early when `encryptionInfo`/`origin` are both null) instead of throwing. Over
   proximity there is no DC-API nonce channel; anti-replay comes from session-transcript freshness +
   the single-use, server-minted `transaction_id`. **Runs on the SoR** (called by
   `commitTransaction`).
2. **Issuer trust anchor** — the records server's `createTrustManager()` (SDK
   `multipaz-records-server` and/or Utopia `organizations/registry/backend`) must add the
   utopia.multipaz.org issuer cert, so a hosted-issued DPC passes `trustManager.verify`.

Plus the Utopia repo edits used by the Docker path: `pp-root` in `registry.conf`, the merchant
account in `records.json`, and `mavenLocal` before the remote snapshot repo (or `exclusiveContent`)
so the image builds against the patched SDK.

---

## Troubleshooting

| Symptom (Declined / server error)                     | Cause                                                             | Fix                                                                                                                          |
|-------------------------------------------------------|-------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `encryptionInfo is required for verifyNonce`          | SoR built **without** the proximity `verifyNonce` patch           | Ensure the SoR resolves the patched `0.101.0-SNAPSHOT` from mavenLocal (Docker: `exclusiveContent`/`--refresh-dependencies`) |
| `Unknown account …`                                   | payer (`payment_instrument_id`) or payee (`20000001`) not seeded  | Seed both via `/identity/load` (Docker seeds from `records.json`)                                                            |
| `…not issued by a trusted issuer`                     | SoR doesn't trust the DPC's issuer                                | Add the issuer cert to the SoR's `createTrustManager()`                                                                      |
| RPC auth / signature failure at the SoR               | `pp-root` not in the SoR config, or `pp-leaf` doesn't chain to it | Set `root_identities.payment_processor = pp-root` on the SoR                                                                 |
| Attestation `register` fails at the terminal backend  | app signing digest / package mismatch                             | The backend logs the digests it saw ("Digest N: …"); copy it into `client_requirements`                                      |
| `This terminal is not configured for settlement`      | no `PaymentSettler` (e.g. iOS build)                              | Card-bound settlement is mandatory; run on Android with the terminal backend reachable                                       |
| `The customer's device did not authorize this amount` | the wallet didn't device-sign the `transaction_data`              | Holder must register `PaymentTransaction` (`addUtopiaTypes()`) and be built from the same SDK                                |

---

## iOS status

**iOS is implemented** — it just needs a macOS build host. The whole flow is
multiplatform: the reader + Compose UI are in `commonMain`, and the settlement logic
(`RpcPaymentSettler`, the `PaymentProcessor` wire calls, and `RpcAuthorizedDeviceClient`) is common
too. Each platform supplies only two things — an HTTP engine and a `SecureArea`:

|             | Android (`AndroidPaymentSettler`) | iOS (`IosPaymentSettler`)                                                                             |
|-------------|-----------------------------------|-------------------------------------------------------------------------------------------------------|
| HTTP engine | `OkHttp`                          | `Darwin`                                                                                              |
| Secure area | `AndroidKeystoreSecureArea`       | `SoftwareSecureArea`                                                                                  |
| Attestation | Android key attestation           | **App Attest** on a real device (hardware `DeviceAttestationIos`); software fallback on the simulator |

So on iOS, `MainViewController` wires `App(settler = IosPaymentSettler())` and settlement works the
same way — the terminal backend just needs an `ios` (and/or `software`) block in its
`client_requirements`, alongside the `android` one.

Kotlin/Native Apple targets (`iosArm64`, `iosSimulatorArm64`) compile only on macOS, and building the POS's iOS targets against the *patched*
SDK needs the SDK's `*-iosarm64` klibs in mavenLocal — which `publishToMavenLocal` emits only when
run on macOS. On a Linux host, no iOS target of this project can be compiled at all; on a Mac,
`publishToMavenLocal -Psnapshot=true` + opening `iosApp/` in Xcode is all it takes.

**Caveats on iOS:** NFC reader mode needs the CoreNFC entitlement on a **signed** build (no
reader-mode NFC on the simulator); the QR + BLE path works without it. App Attest needs a
provisioned app on a real device; the simulator uses the software-attestation fallback.

---

### Running tests

```
./gradlew :shared:testAndroidHostTest
```