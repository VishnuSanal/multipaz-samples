package org.multipaz.transit

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import org.multipaz.context.initializeApplication
import org.multipaz.nfc.handleUsbDeviceAttached
import org.multipaz.transit.nfc.ExternalNfcReaders

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        initializeApplication(applicationContext)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // Auto-configures a supported USB-based external NFC reader when it is plugged in while
    // the app is running, like the multipaz wallet's MainActivity does.
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = IntentCompat.getParcelableExtra(
                intent,
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )
            if (device != null) {
                lifecycle.coroutineScope.launch {
                    ExternalNfcReaders.getStore()?.handleUsbDeviceAttached(device)
                }
            }
        }
    }
}
