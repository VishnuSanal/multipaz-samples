package org.multipaz.pos.nfc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.nfc.ExternalNfcReaderState
import org.multipaz.nfc.ExternalNfcReaderStore

/**
 * A picker for which NFC reader to scan with, adapted from the wallet's
 * SelectExternalNfcReaderScreen: a radio list with the built-in reader first, then each
 * configured external reader with its connection status.
 *
 * @param externalNfcReaderStore the store with the configured external readers.
 * @param selectedReaderId the currently selected reader, or `null` for the built-in reader.
 * @param onSelect called with the newly selected reader id, or `null` for the built-in reader.
 * @param onDismiss called when the dialog is closed.
 */
@Composable
fun SelectNfcReaderDialog(
    externalNfcReaderStore: ExternalNfcReaderStore,
    selectedReaderId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val readers = externalNfcReaderStore.readers.collectAsState().value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("NFC reader", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                ReaderOption(
                    title = "Built-in NFC reader",
                    subtitle = "This device's own NFC antenna",
                    selected = selectedReaderId == null || readers.none { it.id == selectedReaderId },
                    onClick = { onSelect(null) },
                )
                readers.forEach { reader ->
                    val state = reader.observeState().collectAsState(initial = null).value
                    val isConnected = state != null && state != ExternalNfcReaderState.NOT_CONNECTED
                    ReaderOption(
                        title = reader.userDisplayName ?: reader.displayName,
                        subtitle = if (isConnected) "USB reader - connected" else "USB reader - not connected",
                        selected = selectedReaderId == reader.id,
                        onClick = { onSelect(reader.id) },
                    )
                }
                if (readers.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No external readers configured. Plug in a USB NFC reader to add one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun ReaderOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.padding(top = 2.dp, end = 16.dp),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
