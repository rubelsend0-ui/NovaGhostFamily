package com.novaghost.family.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.novaghost.family.core.Freshness

@Composable
private fun glassBorderColors(): List<Color> {
    val hc = LocalHighContrast.current
    return if (hc) listOf(Color.White, Color.White)
    else listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = glassBorderColors()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.03f))))
            .border(1.dp, Brush.linearGradient(colors), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun NeonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, danger: Boolean = false) {
    val c = if (danger) NovaColors.Danger else MaterialTheme.colorScheme.primary
    if (outlined) {
        OutlinedButton(onClick, modifier, enabled, border = androidx.compose.foundation.BorderStroke(1.dp, c),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c)) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        Button(onClick, modifier, enabled, colors = ButtonDefaults.buttonColors(containerColor = c, contentColor = NovaColors.Midnight)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FreshnessChip(f: Freshness, detail: String) {
    val (label, color) = when (f) {
        Freshness.LIVE -> "ACTUAL DATA" to NovaColors.Mint
        Freshness.DELAYED -> "DELAYED" to NovaColors.Warn
        Freshness.UNAVAILABLE -> "UNAVAILABLE" to NovaColors.Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text("$label · $detail", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun GhostCore(progress: Float, title: String, subtitle: String, alert: Boolean = false, modifier: Modifier = Modifier.size(230.dp)) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val ring = if (alert) NovaColors.Danger else primary
    val motion = LocalMotion.current
    val t = rememberInfiniteTransition(label = "core")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.9f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val prog by animateFloatAsState(progress.coerceIn(0f, 1f), tween(if (motion) 900 else 0), label = "prog")
    val sp = if (motion) spin else 0f
    val pl = if (motion) pulse else 1f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = 0.30f), Color.Transparent), radius = r * pl), radius = r * pl)
            val inset = 16.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val tl = Offset(inset, inset)
            rotate(sp) {
                drawArc(secondary.copy(alpha = 0.7f), 0f, 60f, false, tl, arcSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawArc(secondary.copy(alpha = 0.4f), 180f, 40f, false, tl, arcSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            val inset2 = 30.dp.toPx()
            val s2 = Size(size.width - 2 * inset2, size.height - 2 * inset2)
            val tl2 = Offset(inset2, inset2)
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, tl2, s2, style = Stroke(10.dp.toPx()))
            drawArc(ring, -90f, 360f * prog, false, tl2, s2, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = NovaColors.Text)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun BarChart(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier.fillMaxWidth().height(120.dp)) {
    val p = MaterialTheme.colorScheme.primary
    val s = MaterialTheme.colorScheme.secondary
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val n = values.size.coerceAtLeast(1)
            val w = size.width / n
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                drawRect(Brush.verticalGradient(listOf(p, s)), Offset(i * w + w * 0.2f, size.height - h), Size(w * 0.6f, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable fun LoadingState(label: String = "Syncing…") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp)); Muted(label)
    }
}

@Composable
fun StateCard(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}, tint: Color = MaterialTheme.colorScheme.primary) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = tint)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Muted(body)
        if (action != null) NeonButton(action, onAction)
    }
}

@Composable fun EmptyState(title: String, body: String) = StateCard(Icons.Filled.Add, title, body)
@Composable fun ErrorState(msg: String, onRetry: () -> Unit) = StateCard(Icons.Filled.Warning, "Something went wrong", msg, "RETRY", onRetry, NovaColors.Danger)
@Composable fun PermissionState(title: String, body: String, action: String, onAction: () -> Unit) =
    StateCard(Icons.Filled.Lock, title, body, action, onAction, NovaColors.Warn)

@Composable
fun OfflineBanner() {
    Row(Modifier.fillMaxWidth().background(NovaColors.Warn.copy(alpha = 0.18f)).padding(8.dp), horizontalArrangement = Arrangement.Center) {
        Text("OFFLINE — showing last known data; changes sync when you reconnect", style = MaterialTheme.typography.labelSmall, color = NovaColors.Warn)
    }
}

@Composable
fun NotConfiguredCard() = StateCard(
    Icons.Filled.Info, "Backend not configured",
    "No google-services.json was found at build time, so accounts, rooms, push and remote features are OFF. " +
        "Follow README → Firebase setup, rebuild, and they turn on. Local, on-device features still work.",
)

@Composable
fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(body)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onMinus, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
        Text(value, Modifier.widthIn(min = 76.dp), textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onPlus, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
    }
}

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp
}
