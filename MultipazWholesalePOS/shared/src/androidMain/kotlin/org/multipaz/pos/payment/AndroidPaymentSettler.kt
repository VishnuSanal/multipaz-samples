package org.multipaz.pos.payment

import io.ktor.client.engine.okhttp.OkHttp
import org.multipaz.securearea.AndroidKeystoreSecureArea

/**
 * Android [PaymentSettler]: attests a key in the **Android Keystore** (its attestation certifies the
 * app's signing cert + package) and talks to the terminal backend over **OkHttp**. All the RPC logic
 * lives in the common [RpcPaymentSettler]; this only supplies the two Android-specific pieces.
 */
fun AndroidPaymentSettler(
    terminalBackendUrl: String = DEFAULT_TERMINAL_URL,
    payeeAccount: String = DEFAULT_PAYEE_ACCOUNT,
): PaymentSettler = RpcPaymentSettler(
    terminalBackendUrl = terminalBackendUrl,
    payeeAccount = payeeAccount,
    httpClientEngine = OkHttp,
    createSecureArea = { storage -> AndroidKeystoreSecureArea.create(storage) },
)
