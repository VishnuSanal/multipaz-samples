package org.multipaz.pos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.pos.payment.PaymentSettler
import org.multipaz.pos.ui.AmountEntryScreen
import org.multipaz.pos.ui.CheckoutScreen
import org.multipaz.util.Platform
import org.multipaz.pos.ui.PosScaffold
import org.multipaz.pos.ui.PosScreen
import org.multipaz.pos.ui.PosTheme
import org.multipaz.pos.ui.SettlementSuccessScreen
import org.multipaz.pos.ui.rememberPosAppState

@Composable
@Preview
fun App(settler: PaymentSettler? = null) {
    PosTheme {
        PromptDialogs(Platform.promptModel)

        Scaffold { paddingValues ->
            Column(
                modifier = Modifier.padding(paddingValues)
            ) {
                val state = rememberPosAppState()

                PosScaffold {
                    when (state.screen) {
                        PosScreen.AMOUNT_ENTRY ->
                            AmountEntryScreen(onCheckout = state::checkout)

                        PosScreen.CHECKOUT ->
                            CheckoutScreen(
                                amountCents = state.settledCents,
                                onCancel = state::cancel,
                                onSettled = state::settle,
                                settler = settler,
                            )

                        PosScreen.SETTLEMENT ->
                            SettlementSuccessScreen(
                                amountCents = state.settlementAmountCents,
                                onNewTransaction = state::newTransaction,
                                card = state.lastSettlement?.card,
                            )
                    }
                }
            }
        }
    }
}