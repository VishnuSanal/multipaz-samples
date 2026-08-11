package org.multipaz.pos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.multipaz.util.Platform
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.permissions.rememberBluetoothEnabledState
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.compose.qrcode.QrCodeScanner
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.nfc.scanMdocReader
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.pos.payment.PaymentCardReadResult
import org.multipaz.pos.payment.PaymentMethod
import org.multipaz.pos.payment.PaymentSettler
import org.multipaz.pos.payment.SettlementResult
import org.multipaz.pos.payment.readAndVerifyPaymentCard
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.fromBase64Url
import kotlin.time.Clock

private const val TAG = "CardVerification"

/** What the reader is currently doing. NFC listening is the default; QR is opt-in via the button. */
private sealed interface ReaderUiState {
    data object AwaitingTap : ReaderUiState
    data object ScanningQr : ReaderUiState
    data object Verifying : ReaderUiState
    data class Failed(val reason: String) : ReaderUiState
}

/** A captured proximity engagement (from NFC or QR) waiting to be read and verified. */
private data class PendingRead(
    val encodedDeviceEngagement: ByteString,
    val handover: DataItem,
    val transport: MdocTransport?,
    val method: PaymentMethod,
)

/**
 * Reads and cryptographically verifies a presented Utopia Wholesale card, reporting the outcome.
 *
 * This is a real ISO 18013-5 mdoc reader on both Android and iOS — every API it uses (NFC reader
 * mode, the BLE [MdocTransport], the QR camera, and the Bluetooth/camera permission holders) is
 * multiplatform in Multipaz. Bluetooth permission and radio state are tracked reactively via
 * [rememberBluetoothPermissionState] / [rememberBluetoothEnabledState]: while either isn't ready the
 * screen shows a gate whose button requests it, and the flow proceeds automatically once satisfied.
 * The terminal then listens for an NFC tap by default; a "Scan QR code" button flips to the camera
 * (gated on [rememberCameraPermissionState]). Either path reads the customer's Digital Payment
 * Credential, asks the wallet to authorize the amount, and verifies the device response (see
 * [readAndVerifyPaymentCard]).
 *
 * [onApproved] fires once with the verified settlement; [onCancel] returns to the idle screen.
 */
@Composable
fun CardVerificationOverlay(
    amountCents: Long,
    onApproved: (SettlementResult.Approved) -> Unit,
    onCancel: () -> Unit,
    settler: PaymentSettler? = null,
) {
    val blePermission = rememberBluetoothPermissionState()
    val bleEnabled = rememberBluetoothEnabledState()
    val scope = rememberCoroutineScope()

    // Reactive Bluetooth gates — these recompose when the state changes, so granting the permission
    // or turning the radio on makes the gate disappear and the reader start on its own.
    if (!blePermission.isGranted) {
        TerminalPermissionGate(
            icon = Icons.Filled.Bluetooth,
            title = "ALLOW BLUETOOTH",
            explanation = "This terminal reads payment cards over Bluetooth Low Energy. " +
                "It can't accept cards until the permission is granted.",
            buttonLabel = "GRANT PERMISSION",
            onRequest = { scope.launch { blePermission.launchPermissionRequest() } },
            onCancel = onCancel,
        )
        return
    }
    if (!bleEnabled.isEnabled) {
        TerminalPermissionGate(
            icon = Icons.Filled.BluetoothDisabled,
            title = "TURN ON BLUETOOTH",
            explanation = "Bluetooth is switched off. Turn it on so the terminal can exchange data " +
                "with the customer's card.",
            buttonLabel = "TURN ON BLUETOOTH",
            onRequest = { scope.launch { bleEnabled.enable() } },
            onCancel = onCancel,
        )
        return
    }

    ReaderFlow(
        amountCents = amountCents,
        onApproved = onApproved,
        onCancel = onCancel,
        settler = settler,
    )
}

/** The NFC-default / QR-fallback reader, entered once Bluetooth is available. */
@Composable
private fun ReaderFlow(
    amountCents: Long,
    onApproved: (SettlementResult.Approved) -> Unit,
    onCancel: () -> Unit,
    settler: PaymentSettler? = null,
) {
    val cameraPermission = rememberCameraPermissionState()
    val scope = rememberCoroutineScope()
    val reader = remember { NfcTagReader.getReaders().firstOrNull() }

    var uiState by remember { mutableStateOf<ReaderUiState>(ReaderUiState.AwaitingTap) }
    var pendingRead by remember { mutableStateOf<PendingRead?>(null) }
    // Guards against the camera firing onCodeScanned repeatedly once we've captured an engagement.
    var qrHandled by remember { mutableStateOf(false) }

    // Listen for an NFC tap while (and only while) the tap screen is showing. Keyed on uiState so
    // switching to QR or verifying cancels the scan; returning to AwaitingTap restarts it. On a
    // capture it stashes the engagement into pendingRead rather than verifying inline, so flipping
    // uiState to Verifying below can't cancel the verify coroutine.
    LaunchedEffect(uiState) {
        if (uiState != ReaderUiState.AwaitingTap) return@LaunchedEffect
        val r = reader ?: return@LaunchedEffect
        try {
            val scanResult = withContext(Platform.promptModel) {
                r.scanMdocReader(
                    message = null,
                    options = MdocTransportOptions(),
                    handoverOptions = MdocReaderNfcHandoverOptions(),
                    selectConnectionMethod = { connectionMethods -> connectionMethods.first() },
                    negotiatedHandoverConnectionMethods = listOf(
                        MdocConnectionMethodBle(
                            supportsPeripheralServerMode = false,
                            supportsCentralClientMode = true,
                            peripheralServerModeUuid = null,
                            centralClientModeUuid = UUID.randomUUID(),
                        )
                    ),
                )
            }
            if (scanResult == null) {
                uiState = ReaderUiState.Failed("No card detected — try again")
                return@LaunchedEffect
            }
            pendingRead = PendingRead(
                encodedDeviceEngagement = scanResult.encodedDeviceEngagement,
                handover = scanResult.handover,
                transport = scanResult.transport,
                method = PaymentMethod.NFC,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "NFC card verification failed", e)
            uiState = ReaderUiState.Failed(e.message ?: "Card could not be verified")
        }
    }

    // Read + verify whenever an engagement is captured. Keyed on pendingRead so it survives the
    // uiState change to Verifying.
    LaunchedEffect(pendingRead) {
        val read = pendingRead ?: return@LaunchedEffect
        uiState = ReaderUiState.Verifying
        try {
            // Card-bound settlement is mandatory — there is no out-of-band path. A terminal with no
            // settler cannot take payment.
            val activeSettler = settler
                ?: throw IllegalStateException("This terminal is not configured for settlement")
            val now = Clock.System.now()
            // Run under the PromptModel: the settler attests a key in the Android Keystore, which (like
            // the NFC reader) goes through Multipaz's prompt machinery.
            val approved = withContext(Platform.promptModel) {
                // Reserve a transaction on the records server (via the terminal backend) and bind ITS
                // id into the request's transaction_data, so the device-signed amount is matched to the
                // pending ledger entry at commit.
                val transactionId = activeSettler.createTransaction(amountCents).transactionId
                val result = readAndVerifyPaymentCard(
                    read.encodedDeviceEngagement, read.handover, read.transport,
                    amountCents = amountCents,
                    transactionRef = transactionId,
                )
                // Settle on the ledger. Any failure — unbound amount, untrusted issuer, insufficient
                // account, RPC error — throws and declines the sale. The confirmation id becomes the
                // transaction id shown to the cashier.
                val confirmation = activeSettler.commit(result.presentmentRecord)
                approvedResult(result, read.method, amountCents, now, confirmation)
            }
            onApproved(approved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "Card verification failed", e)
            uiState = ReaderUiState.Failed(e.message ?: "Card could not be verified")
        }
    }

    when (val state = uiState) {
        ReaderUiState.AwaitingTap ->
            NfcReadingContent(onUseQr = { uiState = ReaderUiState.ScanningQr }, onCancel = onCancel)

        ReaderUiState.ScanningQr ->
            if (!cameraPermission.isGranted) {
                // Reactive camera gate — grant it and the scanner appears on the next recomposition.
                TerminalPermissionGate(
                    icon = Icons.Filled.PhotoCamera,
                    title = "ALLOW CAMERA",
                    explanation = "The camera is needed to scan the QR code from the customer's wallet.",
                    buttonLabel = "GRANT PERMISSION",
                    onRequest = { scope.launch { cameraPermission.launchPermissionRequest() } },
                    onCancel = { uiState = ReaderUiState.AwaitingTap },
                )
            } else {
                QrScannerModal(
                    onCodeScanned = { qrText ->
                        if (!qrHandled && qrText.startsWith("mdoc:")) {
                            qrHandled = true
                            pendingRead = PendingRead(
                                encodedDeviceEngagement = ByteString(
                                    qrText.substringAfter("mdoc:").fromBase64Url()
                                ),
                                handover = Simple.NULL,
                                transport = null,
                                method = PaymentMethod.QR_CODE,
                            )
                        }
                    },
                    onBack = { uiState = ReaderUiState.AwaitingTap },
                )
            }

        ReaderUiState.Verifying ->
            PaymentProcessingModal(label = "AUTHORIZING PAYMENT…")

        is ReaderUiState.Failed ->
            PaymentDeclinedModal(
                reason = state.reason,
                onDismiss = {
                    qrHandled = false
                    pendingRead = null
                    uiState = ReaderUiState.AwaitingTap
                },
            )
    }
}

@Composable
private fun QrScannerModal(
    onCodeScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    TerminalModalScrim(borderColor = PosTheme.colors.primary) {
        Text(
            "SCAN CUSTOMER QR CODE",
            style = PosTheme.type.headlineMd,
            color = PosTheme.colors.onSurface,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            QrCodeScanner(
                modifier = Modifier.fillMaxSize(),
                cameraSelection = CameraSelection.DEFAULT_BACK_CAMERA,
                captureResolution = CameraCaptureResolution.HIGH,
                showCameraPreview = true,
                onCodeScanned = { code -> if (code != null) onCodeScanned(code) },
            )
        }
        Text(
            "POINT AT THE WALLET'S SHARE QR",
            style = PosTheme.type.labelSm,
            color = PosTheme.colors.onSurface.copy(alpha = 0.6f),
            letterSpacing = 2.sp,
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("BACK TO NFC")
        }
    }
}

/** Builds the approved settlement from the records-server ledger [confirmation] id. */
private fun approvedResult(
    result: PaymentCardReadResult,
    method: PaymentMethod,
    amountCents: Long,
    now: kotlin.time.Instant,
    confirmation: String,
): SettlementResult.Approved =
    SettlementResult.Approved(
        transactionId = "#$confirmation",
        amountCents = amountCents,
        method = method,
        timestampEpochMillis = now.toEpochMilliseconds(),
        card = result.card,
    )
