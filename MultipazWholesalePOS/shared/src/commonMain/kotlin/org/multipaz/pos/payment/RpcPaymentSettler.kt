package org.multipaz.pos.payment

import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.rpc.client.RpcAuthorizedDeviceClient
import org.multipaz.rpc.client.RpcStub
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.handler.RpcReturnCode
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.verification.Iso18013PresentmentRecord
import org.multipaz.verification.PresentmentRecord
import org.multipaz.verification.toDataItem

/** Terminal backend `/rpc`. Emulator/device: host loopback via `adb reverse`; iOS simulator shares the host network. */
const val DEFAULT_TERMINAL_URL = "http://localhost:8110/rpc"
// const val DEFAULT_TERMINAL_URL = "https://<name>.trycloudflare.com/rpc"

/** The merchant's ledger account, sent as the payee in `createTransaction`. */
const val DEFAULT_PAYEE_ACCOUNT = "20000001"

/**
 * Platform-agnostic [PaymentSettler]: performs the device-attestation handshake to the terminal
 * backend and drives the two `PaymentProcessor` RPCs. The app holds no signing key — it authenticates
 * by attesting a key in a platform [SecureArea], and the backend (which checks the attestation against
 * its `client_requirements`) holds the payment key.
 *
 * The only platform-specific inputs are injected:
 *  - [httpClientEngine] — Android supplies `OkHttp`, iOS supplies `Darwin`.
 *  - [createSecureArea] — Android supplies `AndroidKeystoreSecureArea`, iOS `SecureEnclaveSecureArea`.
 *
 * See [androidPaymentSettler] / `iosPaymentSettler` for the thin per-platform factories.
 */
class RpcPaymentSettler(
    private val terminalBackendUrl: String,
    private val payeeAccount: String,
    private val httpClientEngine: HttpClientEngineFactory<*>,
    private val createSecureArea: suspend (Storage) -> SecureArea,
) : PaymentSettler {

    private val connectMutex = Mutex()
    private var deviceClient: RpcAuthorizedDeviceClient? = null

    private suspend fun deviceClient(): RpcAuthorizedDeviceClient = connectMutex.withLock {
        deviceClient ?: run {
            // Ephemeral per-process identity: a device-attestation key in the platform secure area (its
            // attestation certifies the app's identity), plus a Hosts table for the registered clientId.
            // Registration + the attestation check happen inside connect().
            val storage = EphemeralStorage()
            val secureArea = createSecureArea(storage)
            RpcAuthorizedDeviceClient.connect(
                exceptionMap = RpcExceptionMap.Builder().build(),
                httpClientEngine = httpClientEngine,
                url = terminalBackendUrl,
                secureArea = secureArea,
                storage = storage,
                secret = null,
            ).also { deviceClient = it }
        }
    }

    override suspend fun createTransaction(amountCents: Long): SettlementTransaction =
        SettlementTransaction(
            transactionId = withProcessor {
                it.createTransaction(payeeAccount, amountCents / 100.0, "USD", null)
            }
        )

    override suspend fun commit(record: Iso18013PresentmentRecord): String =
        withProcessor { it.commit(record) }

    private suspend fun <T> withProcessor(block: suspend (PaymentProcessorClient) -> T): T {
        val client = deviceClient()
        return withContext(RpcAuthClientSession()) {
            block(PaymentProcessorClient("payment", client.dispatcher, client.notifier))
        }
    }
}

/**
 * Hand-rolled equivalent of the generated `PaymentProcessorStub` (the JVM-only `multipaz-server`
 * module that defines it isn't consumable from a KMP app). Replicates the two calls' wire format:
 * params = `[rpcState, arg]`; response = `[nextState, _, returnCode, result]`. Works against either
 * the records server or the terminal backend — both expose the `payment` endpoint.
 */
private class PaymentProcessorClient(
    endpoint: String,
    dispatcher: RpcDispatcher,
    notifier: RpcNotifier,
) : RpcStub(endpoint, dispatcher, notifier, Bstr(byteArrayOf())) {

    suspend fun createTransaction(
        payeeAccount: String,
        amount: Double,
        currency: String,
        description: String?,
    ): String {
        val builder = CborMap.builder()
        builder.put("payeeAccount", Tstr(payeeAccount))
        builder.put("amount", amount.toDataItem())
        builder.put("currency", Tstr(currency))
        if (description != null) builder.put("description", Tstr(description))
        val params = buildCborArray {
            add(rpcState)
            add(builder.end().build())
        }
        val response = rpcDispatcher.dispatch(rpcEndpoint, "create", params)
        rpcState = response[0]
        if (response[2].asNumber.toInt() != RpcReturnCode.RESULT.ordinal) {
            rpcDispatcher.exceptionMap.handleExceptionReturn(response)
        }
        return response[3]["transactionId"].asTstr
    }

    suspend fun commit(record: PresentmentRecord): String {
        val params = buildCborArray {
            add(rpcState)
            add(record.toDataItem())
        }
        val response = rpcDispatcher.dispatch(rpcEndpoint, "commit", params)
        rpcState = response[0]
        if (response[2].asNumber.toInt() != RpcReturnCode.RESULT.ordinal) {
            rpcDispatcher.exceptionMap.handleExceptionReturn(response)
        }
        return response[3].asTstr
    }
}
