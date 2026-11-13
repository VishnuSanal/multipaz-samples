package org.multipaz.pos.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.nfc.ExternalNfcReader
import org.multipaz.nfc.ExternalNfcReaderState
import org.multipaz.nfc.ExternalNfcReaderStore
import org.multipaz.nfc.NfcTagReader
import org.multipaz.util.Logger
import org.multipaz.util.Platform

private const val TAG = "ExternalNfcReaders"

/**
 * Holds the app-wide [ExternalNfcReaderStore].
 *
 * A singleton because two parts of the app need the same instance: the Compose UI, which
 * lists the configured readers in the reader picker, and the Android MainActivity, which
 * auto-adds a supported USB NFC reader when it is plugged in while the app is running.
 */
object ExternalNfcReaders {
    private val mutex = Mutex()
    private var store: ExternalNfcReaderStore? = null

    /**
     * Gets the store, creating it on first use, or `null` if it can't be created - the
     * app then runs with the built-in NFC reader only.
     */
    suspend fun getStore(): ExternalNfcReaderStore? {
        return mutex.withLock {
            store ?: try {
                ExternalNfcReaderStore.create(Platform.nonBackedUpStorage)
                    .also { store = it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Error creating ExternalNfcReaderStore", e)
                null
            }
        }
    }
}

/**
 * Resolves the reader to scan with: [externalReader] when it is connected, requesting USB
 * permission if needed, otherwise falling back to the platform's built-in reader.
 *
 * This mirrors how the multipaz wallet resolves the reader in its
 * VerificationProximityTransferScreen.
 *
 * @param externalReader the configured external reader to use, or `null` for the built-in
 *   reader.
 * @return a [NfcTagReader], or `null` if the platform has no NFC at all.
 */
suspend fun resolveNfcTagReader(externalReader: ExternalNfcReader?): NfcTagReader? {
    val builtInReader = NfcTagReader.getReaders().firstOrNull()
    if (externalReader == null) {
        return builtInReader
    }
    return try {
        when (externalReader.observeState().first()) {
            ExternalNfcReaderState.NOT_CONNECTED -> {
                Logger.w(TAG, "External reader '${externalReader.displayName}' is not connected")
                builtInReader
            }
            ExternalNfcReaderState.CONNECTED_NO_PERMISSION -> {
                if (externalReader.requestPermission()) {
                    externalReader.getNfcTagReader()
                } else {
                    Logger.w(TAG, "Permission denied for external reader '${externalReader.displayName}'")
                    builtInReader
                }
            }
            ExternalNfcReaderState.CONNECTED -> externalReader.getNfcTagReader()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Error getting external NFC tag reader", e)
        builtInReader
    }
}
