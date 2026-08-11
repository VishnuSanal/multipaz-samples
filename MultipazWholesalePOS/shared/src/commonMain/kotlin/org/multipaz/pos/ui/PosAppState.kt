package org.multipaz.pos.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.multipaz.pos.payment.SettlementResult

/**
 * Drives the terminal flow: which [PosScreen] is showing, the amount carried from
 * checkout into settlement, and the settlement result. A plain Compose state holder
 * with no Android dependency, so the transitions can be unit-tested in commonTest.
 */
@Stable
class PosAppState {
    var screen by mutableStateOf(PosScreen.AMOUNT_ENTRY)
        private set

    var settledCents by mutableLongStateOf(0L)
        private set

    /** The approved settlement backing the confirmation screen, or null before one exists. */
    var lastSettlement by mutableStateOf<SettlementResult.Approved?>(null)
        private set

    /** Amount shown on the settlement screen. */
    val settlementAmountCents: Long
        get() = lastSettlement?.amountCents ?: settledCents

    /** Amount entered → move to the payment screen. */
    fun checkout(cents: Long) {
        settledCents = cents
        screen = PosScreen.CHECKOUT
    }

    /** Payment authorized → record the result and show the settlement confirmation. */
    fun settle(result: SettlementResult.Approved) {
        lastSettlement = result
        settledCents = result.amountCents
        screen = PosScreen.SETTLEMENT
    }

    /** Abort the payment and return to amount entry. */
    fun cancel() {
        screen = PosScreen.AMOUNT_ENTRY
    }

    /** Start a fresh transaction from the settlement screen. */
    fun newTransaction() {
        settledCents = 0L
        lastSettlement = null
        screen = PosScreen.AMOUNT_ENTRY
    }
}

@Composable
fun rememberPosAppState(): PosAppState = remember { PosAppState() }
