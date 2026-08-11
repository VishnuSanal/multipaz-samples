package org.multipaz.pos.payment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.Constants
import org.multipaz.verification.Iso18013PresentmentRecord
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "PaymentCardReader"

/**
 * The Utopia Wholesale Digital Payment Credential (DPC) as an ISO 18013-5 mdoc. Both the docType
 * and namespace are `org.multipaz.payment.sca.1` (see `org.multipaz.utopia.knowntypes.
 * DigitalPaymentCredential` and the SCA payment profile in Multipaz). The values are inlined here
 * rather than depending on `multipaz-utopia`, which is only published as a snapshot; these strings
 * are what the paired Utopia holder actually presents.
 */
object DigitalPaymentCredential {
    const val DOCTYPE = "org.multipaz.payment.sca.1"
    const val NAMESPACE = "org.multipaz.payment.sca.1"

    const val ISSUER_NAME = "issuer_name"
    const val PAYMENT_INSTRUMENT_ID = "payment_instrument_id"
    const val MASKED_ACCOUNT_REFERENCE = "masked_account_reference"
    const val HOLDER_NAME = "holder_name"
    const val ISSUE_DATE = "issue_date"
    const val EXPIRY_DATE = "expiry_date"

    /** Data elements the terminal asks for. `intentToRetain=false` — the POS does not store them. */
    val REQUESTED_ELEMENTS = listOf(
        ISSUER_NAME, HOLDER_NAME, MASKED_ACCOUNT_REFERENCE, PAYMENT_INSTRUMENT_ID,
        ISSUE_DATE, EXPIRY_DATE,
    )
}

/** The terminal's identity as the payee in the SCA payment `transaction_data`. */
private const val TERMINAL_PAYEE_NAME = "Utopia Wholesale POS"
private const val TERMINAL_PAYEE_ID = "utopia-wholesale-pos-terminal-01"
private const val TERMINAL_CURRENCY = "USD"

/**
 * Whether to bind the amount into the proximity request via SCA payment `transaction_data`.
 *
 * A holder that registers [PaymentTransaction] (via `addUtopiaTypes()`) device-signs the hash of the
 * transaction data over proximity just as it does over OpenID4VP — there is no per-mechanism gate.
 * The earlier "session ended" failures were a **transaction_data encoding mismatch**: the reader must
 * emit exactly `Tagged(ENCODED_CBOR, Bstr(PaymentTransaction.serializeCbor(payload)))`, which the
 * holder decodes with `PaymentTransaction.parseCbor`; any other shape makes the holder's
 * `extractTransactionData` throw and close the transport. This requires the reader and holder to be
 * built against the **same Multipaz SDK** (matching `serializeCbor`/`parseCbor`).
 */
private const val ATTEMPT_AMOUNT_BINDING = true

/**
 * What the terminal learned from a presented DPC. The read only succeeds when the holder's device
 * cryptographically authorized the exact amount (card-bound settlement is mandatory — see
 * [readAndVerifyPaymentCard]).
 */
data class PaymentCardReadResult(
    val card: PaymentCardDetails,
    /**
     * The proximity presentment packaged for the records server. Submit it to
     * `PaymentProcessor.commitTransaction` to settle on the ledger. `encryptionInfo`/`origin` are null
     * (this is BLE/NFC, not the DC API), which the SoR's proximity `verifyNonce` path accepts.
     */
    val presentmentRecord: Iso18013PresentmentRecord,
)

/**
 * Reads the Utopia Wholesale Digital Payment Credential offered at
 * [encodedDeviceEngagement]/[handover] and requires the wallet to cryptographically authorize
 * [amountCents], returning the verified card + the presentment to settle on the ledger.
 *
 * This is the reader half of an ISO 18013-5 proximity exchange. Alongside the DPC claims it attaches
 * an SCA payment `transaction_data` object ([PaymentTransaction], carrying the amount/currency/payee
 * and [transactionRef]) into the `DeviceRequest`'s `requestInfo`. The wallet device-signs the hash of
 * that transaction data and returns it in the `urn:eudi:sca:payment:1` device-signed namespace; we
 * recompute the same hash and compare. **Card-bound settlement is mandatory**: if the customer's
 * device did not commit to *this* exact amount, this function throws and the sale is declined — there
 * is no out-of-band capture path.
 *
 * [DeviceResponse.verify] validates the issuer signature (MSO), the device signature/MAC, and the
 * value digests. It throws on any cryptographic failure, so a returned card means the response was
 * sound. It does NOT check the issuer certificate against a trust anchor — a
 * production payment terminal MUST additionally verify `document.issuerCertChain` against the Utopia
 * issuer root before honoring the payment (this is what the utopia upay backend does via its
 * `TrustManager`).
 *
 * Runs on whatever coroutine context the caller provides; transport I/O is suspending. Throws on
 * transport errors, an empty/terminated session, a missing document, or verification failure — the
 * overlay turns those into a declined result the cashier can retry.
 */
suspend fun readAndVerifyPaymentCard(
    encodedDeviceEngagement: ByteString,
    handover: DataItem,
    existingTransport: MdocTransport?,
    amountCents: Long,
    transactionRef: String,
): PaymentCardReadResult {
    val deviceEngagement = DeviceEngagement.fromDataItem(
        Cbor.decode(encodedDeviceEngagement.toByteArray())
    )
    val eDeviceKey = deviceEngagement.eDeviceKey

    // Ephemeral reader key + ISO 18013-5 session transcript: [ DE, EReaderKey, Handover ].
    val eReaderKey = Crypto.createEcPrivateKey(eDeviceKey.curve)
    val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
    val sessionTranscript = buildCborArray {
        add(Tagged(24, Bstr(encodedDeviceEngagement.toByteArray())))
        add(Tagged(24, Bstr(encodedEReaderKey)))
        add(handover)
    }
    val encodedSessionTranscript = Cbor.encode(sessionTranscript)

    // Build the DPC request. When [ATTEMPT_AMOUNT_BINDING] is on, attach the SCA payment
    // transaction_data so a supporting wallet device-signs the amount. It's off by default because a
    // proximity wallet that doesn't support SCA binding drops the credential or ends the session when
    // it sees the transaction — see the KDoc on ATTEMPT_AMOUNT_BINDING. Only one request is sent per
    // session: this holder does not accept a second request after ending the first.
    val transactionSerialized: ByteString? = if (ATTEMPT_AMOUNT_BINDING) {
        val payload = PaymentTransaction.Payload(
            transactionId = transactionRef,
            currency = TERMINAL_CURRENCY,
            amount = amountCents / 100.0,
            payee = PaymentTransaction.Payee(
                name = TERMINAL_PAYEE_NAME,
                id = TERMINAL_PAYEE_ID,
            ),
        )
        // This exact byte string is what the holder parses (PaymentTransaction.parseCbor) and what
        // the device-signed transaction_data_hash is computed over.
        PaymentTransaction.serializeCbor(payload, hashAlgorithms = null)
    } else {
        null
    }

    val deviceRequest = buildDeviceRequest(sessionTranscript) {
        addDocRequest(
            docType = DigitalPaymentCredential.DOCTYPE,
            nameSpaces = mapOf(
                DigitalPaymentCredential.NAMESPACE to
                    DigitalPaymentCredential.REQUESTED_ELEMENTS.associateWith { false }
            ),
            docRequestInfo = transactionSerialized?.let {
                DocRequestInfo(
                    otherInfo = mapOf(
                        PaymentTransaction.mdocRequestInfoKeyName to
                            Tagged(Tagged.ENCODED_CBOR, Bstr(it.toByteArray()))
                    )
                )
            },
        )
    }
    val encodedDeviceRequest = Cbor.encode(deviceRequest.toDataItem())

    val transport = existingTransport ?: run {
        val connectionMethods = MdocConnectionMethod.disambiguate(
            deviceEngagement.connectionMethods,
            MdocRole.MDOC_READER,
        )
        val connectionMethod = connectionMethods.firstOrNull()
            ?: throw IllegalStateException("Card offered no supported connection method")
        MdocTransportFactory.Default.createTransport(
            connectionMethod = connectionMethod,
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
        )
    }

    val sessionEncryption = SessionEncryption(
        MdocRole.MDOC_READER,
        eReaderKey,
        eDeviceKey,
        encodedSessionTranscript,
    )

    val encodedDeviceResponse: ByteArray = try {
        transport.open(eDeviceKey)
        transport.sendMessage(
            sessionEncryption.encryptMessage(messagePlaintext = encodedDeviceRequest, statusCode = null)
        )
        val sessionData = transport.waitForMessage()
        if (sessionData.isEmpty()) {
            throw IllegalStateException("Customer device ended the session before responding")
        }
        val (message, status) = sessionEncryption.decryptMessage(sessionData)
        // Politely terminate the session if the holder didn't already.
        runCatching {
            if (status != Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                transport.sendMessage(
                    SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                )
            }
        }
        message ?: throw IllegalStateException("No credential in the customer's response")
    } finally {
        runCatching { transport.close() }
    }

    val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
    // Verify issuer + device signatures. We pass an empty DocumentTypeRepository (no transaction
    // types registered) so verify() does NOT hard-enforce the SCA transaction hash — we check the
    // binding ourselves below against the (now signature-verified) device-signed namespaces.
    deviceResponse.verify(
        sessionTranscript = sessionTranscript,
        eReaderKey = AsymmetricKey.anonymous(eReaderKey, eDeviceKey.curve.defaultKeyAgreementAlgorithm),
        deviceRequest = deviceRequest,
        documentTypeRepository = DocumentTypeRepository(),
        atTime = Clock.System.now(),
    )

    val document = deviceResponse.documents.firstOrNull { it.docType == DigitalPaymentCredential.DOCTYPE }
        ?: deviceResponse.documents.firstOrNull()
        ?: throw IllegalStateException("Verified response contained no documents")

    if (document.docType != DigitalPaymentCredential.DOCTYPE) {
        Logger.w(
            TAG,
            "Presented docType ${document.docType} is not a Utopia Wholesale payment card",
        )
    }

    val elements = document.issuerNamespaces.data[DigitalPaymentCredential.NAMESPACE].orEmpty()
    fun text(elementId: String): String? =
        elements[elementId]?.dataElementValue?.let { value ->
            runCatching { value.asTstr }.getOrNull()
        }

    val card = PaymentCardDetails(
        issuerName = text(DigitalPaymentCredential.ISSUER_NAME),
        holderName = text(DigitalPaymentCredential.HOLDER_NAME),
        maskedAccountReference = text(DigitalPaymentCredential.MASKED_ACCOUNT_REFERENCE),
        paymentInstrumentId = text(DigitalPaymentCredential.PAYMENT_INSTRUMENT_ID),
        expiryDate = text(DigitalPaymentCredential.EXPIRY_DATE),
    )

    // Did the wallet device-sign our exact amount? Only meaningful when we actually attached the
    // transaction (binding enabled). Mirror MdocDocument.verify's transaction check, but without
    // throwing when the response is absent. The device-signed namespaces were just signature-verified
    // above, so a matching hash proves the holder committed to this amount.
    val amountAuthorized = transactionSerialized != null && runCatching {
        val response = document.deviceNamespaces.data[PaymentTransaction.mdocResponseNamespace]
            ?: return@runCatching false
        val presentedHash = response["transaction_data_hash"] as? Bstr ?: return@runCatching false
        val hashAlg = response["transaction_data_hash_alg"]
            ?.let { Algorithm.fromCoseAlgorithmIdentifier(it.asNumber.toInt()) }
            ?: Algorithm.SHA256
        // The holder hashes the exact serialized transaction_data bytes we sent; recompute the same.
        val expectedHash = ByteString(Crypto.digest(hashAlg, transactionSerialized.toByteArray()))
        ByteString(presentedHash.asBstr) == expectedHash
    }.getOrElse { e ->
        Logger.w(TAG, "Could not evaluate payment amount binding", e)
        false
    }

    // Card-bound settlement is mandatory: reject the read unless the customer's device signed *this*
    // exact amount. (The records server independently re-checks this at commit; failing fast here
    // gives the cashier a clear decline instead of a cryptic server error.)
    check(amountAuthorized) {
        "The customer's device did not authorize this amount"
    }

    // Package the raw proximity exchange for server-side settlement. The records server re-verifies
    // the issuer + device signatures itself; eDeviceKey carries our ephemeral reader key so it can
    // check a MAC-authenticated response, and encryptionInfo/origin are null
    val presentmentRecord = Iso18013PresentmentRecord(
        response = Cbor.decode(encodedDeviceResponse),
        sessionTranscript = sessionTranscript,
        request = deviceRequest.toDataItem(),
        eDeviceKey = eReaderKey,
        encryptionInfo = null,
        origin = null,
    )

    return PaymentCardReadResult(
        card = card,
        presentmentRecord = presentmentRecord,
    )
}
