package org.multipaz.pos

import androidx.compose.ui.window.ComposeUIViewController
import org.multipaz.pos.payment.IosPaymentSettler

fun MainViewController() = ComposeUIViewController { App(settler = IosPaymentSettler()) }