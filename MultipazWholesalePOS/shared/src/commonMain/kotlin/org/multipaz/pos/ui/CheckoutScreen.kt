package org.multipaz.pos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.multipaz.pos.payment.PaymentSettler
import org.multipaz.pos.payment.SettlementResult

/**
 * Card-collection screen. The terminal reads over NFC by default, with a QR-camera fallback; the
 * whole flow is a real Multipaz mdoc reader ([CardVerificationOverlay]) on both Android and iOS.
 * [settler], when non-null, settles the read on the records-server ledger.
 */
@Composable
fun CheckoutScreen(
    amountCents: Long,
    onCancel: () -> Unit,
    onSettled: (SettlementResult.Approved) -> Unit,
    settler: PaymentSettler? = null,
) {
    Box(Modifier.fillMaxSize()) {
        CardVerificationOverlay(
            amountCents = amountCents,
            onApproved = onSettled,
            onCancel = onCancel,
            settler = settler,
        )
    }
}

/**
 * Default card-collection view: the terminal is listening for an NFC tap, with a button to switch to
 * QR proximity sharing instead. Shared by the Android reader and the simulated fallback so both look
 * identical. [onNfcTap] is null on Android (the reader scans automatically); the simulated platforms
 * pass a callback so tapping the card stands in for presenting a real one.
 */
@Composable
internal fun NfcReadingContent(
    onUseQr: () -> Unit,
    onCancel: () -> Unit,
    onNfcTap: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        NfcCard(Modifier.weight(1f), onTap = onNfcTap)
        QrProximityButton(onClick = onUseQr)
        CheckoutFooter(onCancel = onCancel)
    }
}

@Composable
private fun NfcCard(modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val c = PosTheme.colors
    val type = PosTheme.type
    val t = rememberInfiniteTransition(label = "nfc")
    val pulse by t.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "nfcPulse",
    )
    PaymentCard(modifier, onClick = onTap) {
        Box(
            Modifier
                .width(220.dp)
                .aspectRatio(1f)
                .clip(CircleShape)
                .border(4.dp, c.primary.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Contactless,
                contentDescription = null,
                tint = c.primary.copy(alpha = pulse),
                modifier = Modifier.size(110.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("READY FOR TAP", style = type.headlineMd, color = c.primary)
        Text(
            "HOLD CARD OR DEVICE NEAR TERMINAL",
            style = type.labelSm,
            color = c.onSurface.copy(alpha = 0.6f),
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun QrProximityButton(onClick: () -> Unit) {
    val c = PosTheme.colors
    val type = PosTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(c.surfaceContainerLow)
            .border(1.dp, c.primary.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.QrCodeScanner,
            contentDescription = null,
            tint = c.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("SCAN QR CODE", style = type.headlineMd, color = c.primary)
    }
}

@Composable
private fun PaymentCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = PosTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun CheckoutFooter(onCancel: () -> Unit) {
    val c = PosTheme.colors
    val type = PosTheme.type
    Column(
        Modifier.fillMaxWidth().widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Cancel
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.errorContainer)
                .border(1.dp, c.error.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .clickable(onClick = onCancel),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Cancel,
                contentDescription = null,
                tint = c.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text("CANCEL TRANSACTION", style = type.headlineMd, color = c.onErrorContainer)
        }
    }
}
