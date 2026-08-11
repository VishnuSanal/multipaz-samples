package org.multipaz.pos

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import org.multipaz.pos.payment.AndroidPaymentSettler

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Wire the app context into Multipaz before any card is read (NFC readers / BLE transports
        // resolve it). Safe to call on every start.
        initializePosTerminal(applicationContext)

        val settler = AndroidPaymentSettler()

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(settler = settler)
        }
    }
}