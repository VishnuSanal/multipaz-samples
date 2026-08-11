package org.multipaz.pos

import android.content.Context
import org.multipaz.context.initializeApplication

/**
 * Initializes Multipaz with the application context. Must be called before the terminal reads a
 * card, because [org.multipaz.nfc.NfcTagReader.getReaders] and the BLE transports resolve the app
 * context set here. Call once from the Android entry point (see MainActivity).
 */
fun initializePosTerminal(context: Context) {
    initializeApplication(context.applicationContext)
}
