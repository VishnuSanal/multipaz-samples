package org.multipaz.pos.payment

/** How the customer presented their card at the terminal. */
enum class PaymentMethod { NFC, QR_CODE }

/**
 * Details read from a customer's Utopia Wholesale Digital Payment Credential (DPC) — an ISO 18013-5
 * mdoc with docType `org.multipaz.payment.sca.1` (see `DigitalPaymentCredential`). Populated when
 * the card is presented over NFC/BLE and the device response passes cryptographic verification.
 *
 * An instance only exists once the mdoc's issuer and device signatures have validated — but that
 * means the response was cryptographically sound, not that the issuer was checked against a trust
 * anchor (see the reader for that boundary).
 */
data class PaymentCardDetails(
    val issuerName: String? = null,
    val holderName: String? = null,
    val maskedAccountReference: String? = null,
    val paymentInstrumentId: String? = null,
    val expiryDate: String? = null,
) {
    /** Best-effort display name for the settlement screen. */
    val displayName: String?
        get() = holderName?.ifBlank { null }
}

/** Outcome of a settlement attempt. */
sealed interface SettlementResult {
    data class Approved(
        val transactionId: String,
        val amountCents: Long,
        val method: PaymentMethod,
        val timestampEpochMillis: Long,
        /** Payment card read from the presented DPC. */
        val card: PaymentCardDetails? = null,
    ) : SettlementResult

    data class Declined(val reason: String) : SettlementResult
}
