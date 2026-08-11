package org.multipaz.pos.payment

import org.multipaz.verification.Iso18013PresentmentRecord

/** A transaction reserved on the records server (SoR): the server-minted id we settle against. */
data class SettlementTransaction(
    val transactionId: String,
)

/**
 * Settles a payment by talking directly to the Multipaz records server (System of Record) —
 * `createTransaction` to reserve, then `commitTransaction` with the customer's device-signed
 * presentment to move funds on the ledger.
 *
 * This is a platform concern (it needs an HTTP client + device attestation to the terminal backend),
 * so the implementation is provided per-platform and injected into the UI. Card-bound settlement is
 * mandatory: a terminal with no [PaymentSettler] cannot take payment — the sale is declined rather
 * than captured out-of-band.
 */
interface PaymentSettler {
    /** Reserve a transaction on the SoR for [amountCents]; the returned id is bound into the request. */
    suspend fun createTransaction(amountCents: Long): SettlementTransaction

    /** Commit the device-signed proximity presentment to the ledger; returns the confirmation id. */
    suspend fun commit(record: Iso18013PresentmentRecord): String
}
