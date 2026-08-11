package org.multipaz.pos.payment

import io.ktor.client.engine.darwin.Darwin
import org.multipaz.securearea.software.SoftwareSecureArea

/**
 * iOS [PaymentSettler]: talks to the terminal backend over Ktor's **Darwin** engine, with all RPC
 * logic in the common [RpcPaymentSettler].
 *
 * iOS device attestation is handled by Multipaz's `DeviceCheck` actual: on a real, provisioned device
 * it uses **App Attest** (a hardware-backed `DeviceAttestationIos`) and the [SoftwareSecureArea] below
 * is unused; on the **simulator** (or when App Attest is unavailable) it falls back to a software
 * attestation that *does* use this secure area. Using `SoftwareSecureArea` therefore works in both
 * places without weakening real-device security.
 *
 * For the terminal backend to accept iOS clients, its `client_requirements` needs an `ios` block
 * (`app_identifiers`, `release_build`) for real devices and/or a `software` block for the simulator —
 * the same way the `android` block accepts Android clients.
 *
 * NB: NFC reader mode on iOS requires the CoreNFC entitlement on a **signed** build (not the
 * simulator); the QR + BLE path works without it.
 */
fun IosPaymentSettler(
    terminalBackendUrl: String = DEFAULT_TERMINAL_URL,
    payeeAccount: String = DEFAULT_PAYEE_ACCOUNT,
): PaymentSettler = RpcPaymentSettler(
    terminalBackendUrl = terminalBackendUrl,
    payeeAccount = payeeAccount,
    httpClientEngine = Darwin,
    createSecureArea = { storage -> SoftwareSecureArea.create(storage) },
)
