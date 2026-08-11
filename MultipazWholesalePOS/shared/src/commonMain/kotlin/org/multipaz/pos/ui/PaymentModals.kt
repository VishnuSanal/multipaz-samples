package org.multipaz.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen scrim shared by every checkout overlay so the simulated and real (Android) card
 * verification paths present with the same terminal chrome. Extracted from CheckoutScreen so the
 * platform-specific reader UI in androidMain can reuse it without duplicating styling.
 */
@Composable
internal fun TerminalModalScrim(
    borderColor: Color,
    content: @Composable () -> Unit,
) {
    val c = PosTheme.colors
    Box(
        Modifier.fillMaxSize().background(c.surface.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surfaceContainerHighest)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = { content() },
        )
    }
}

/**
 * A gate shown while a prerequisite (a permission, or Bluetooth being on) isn't satisfied. The
 * caller drives this reactively — it renders whenever the tracked state is not yet good, and the
 * host recomposes it away once [onRequest] flips the state. [onRequest] triggers the OS prompt (or
 * the enable-Bluetooth flow); [onCancel] abandons the transaction.
 */
@Composable
internal fun TerminalPermissionGate(
    icon: ImageVector,
    title: String,
    explanation: String,
    buttonLabel: String,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = PosTheme.colors
    val type = PosTheme.type
    TerminalModalScrim(borderColor = c.primary) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Text(title, style = type.headlineMd, color = c.onSurface, textAlign = TextAlign.Center)
        Text(
            explanation,
            style = type.labelMd,
            color = c.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.primary)
                .clickable(onClick = onRequest),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(buttonLabel, style = type.headlineMd, color = c.onPrimary, letterSpacing = 2.sp)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, c.outlineVariant, RoundedCornerShape(4.dp))
                .clickable(onClick = onCancel),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CANCEL", style = type.headlineMd, color = c.onSurface.copy(alpha = 0.7f), letterSpacing = 2.sp)
        }
    }
}

@Composable
internal fun PaymentProcessingModal(label: String = "PROCESSING...") {
    val c = PosTheme.colors
    val type = PosTheme.type
    TerminalModalScrim(borderColor = c.primary) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Text(label, style = type.headlineMd, color = c.onSurface, textAlign = TextAlign.Center)
        LinearProgressIndicator()
    }
}

@Composable
internal fun PaymentDeclinedModal(reason: String, onDismiss: () -> Unit) {
    val c = PosTheme.colors
    val type = PosTheme.type
    TerminalModalScrim(borderColor = c.error) {
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(c.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = c.onErrorContainer, modifier = Modifier.size(40.dp))
        }
        Text("DECLINED", style = type.headlineMd, color = c.onSurface)
        Text(
            reason.ifEmpty { "PAYMENT_DECLINED" },
            style = type.labelMd,
            color = c.error,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, c.error, RoundedCornerShape(4.dp))
                .clickable(onClick = onDismiss),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DISMISS", style = type.headlineMd, color = c.error, letterSpacing = 2.sp)
        }
    }
}
