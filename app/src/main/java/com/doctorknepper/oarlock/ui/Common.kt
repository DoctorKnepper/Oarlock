package com.doctorknepper.oarlock.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.core.humanBytes

val LocalArk = staticCompositionLocalOf { ArkTheme.all.first() }

fun ArkTheme.scheme(): ColorScheme = if (dark) darkColorScheme(
    primary = c(accent), onPrimary = c(onAccent), secondary = c(accent2), onSecondary = c(onAccent),
    background = c(bg), onBackground = c(text), surface = c(bg), onSurface = c(text), surfaceVariant = c(surface2),
    onSurfaceVariant = c(muted), surfaceContainer = c(surface), surfaceContainerHigh = c(surface2), surfaceContainerHighest = c(surface2),
    surfaceContainerLow = c(surface), surfaceContainerLowest = c(bg), outline = c(line), outlineVariant = c(line), error = c(danger),
    primaryContainer = c(surface2), onPrimaryContainer = c(accent), secondaryContainer = c(surface2), onSecondaryContainer = c(accent2),
) else lightColorScheme(
    primary = c(accent), onPrimary = c(onAccent), secondary = c(accent2), onSecondary = c(onAccent),
    background = c(bg), onBackground = c(text), surface = c(bg), onSurface = c(text), surfaceVariant = c(surface2),
    onSurfaceVariant = c(muted), surfaceContainer = c(surface), surfaceContainerHigh = c(surface2), surfaceContainerHighest = c(surface2),
    surfaceContainerLow = c(surface), surfaceContainerLowest = c(bg), outline = c(line), outlineVariant = c(line), error = c(danger),
    primaryContainer = c(surface2), onPrimaryContainer = c(accent), secondaryContainer = c(surface2), onSecondaryContainer = c(accent2),
)

val Mono = FontFamily.Monospace

@Composable
fun ArkMaterial(theme: ArkTheme, content: @Composable () -> Unit) {
    val base = Typography()
    val typo = base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalArk provides theme) {
        MaterialTheme(colorScheme = theme.scheme(), typography = typo) {
            // Text outside a Surface would otherwise default to black, invisible on OLED black.
            CompositionLocalProvider(LocalContentColor provides theme.c(theme.text), content = content)
        }
    }
}

/** Small uppercase label in monospace — the "field manual" voice used for section headers. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalArk.current.c(LocalArk.current.muted)) {
    Text(text.uppercase(), modifier = modifier, style = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.6.sp, color = color, fontWeight = FontWeight.Medium))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    val t = LocalArk.current
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = t.c(t.bg), titleContentColor = t.c(t.text), navigationIconContentColor = t.c(t.accent), actionIconContentColor = t.c(t.accent)),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalArk.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.c(t.surface))
            .border(1.dp, t.c(t.line), RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        content = content,
    )
}

data class StorageInfo(val free: Long, val total: Long, val ark: Long, val label: String) {
    val other get() = (total - free - ark).coerceAtLeast(0)
}

fun storageInfo(): StorageInfo {
    val v = Ark.storage.current()
    return StorageInfo(v.free(), v.total(), Ark.store.totalBytes(), v.label)
}

/** Shows how the phone's storage is used: Ark packs, everything else, and free space. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageBar(info: StorageInfo, pending: Long = 0, modifier: Modifier = Modifier, showManage: Boolean = true) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(info.label)
            Spacer(Modifier.weight(1f))
            Text("${humanBytes(info.free)} free of ${humanBytes(info.total)}", style = MaterialTheme.typography.labelMedium, color = t.c(t.text))
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val w = size.width; val h = size.height
            drawRoundRect(t.c(t.line), size = Size(w, h), cornerRadius = CornerRadius(h / 2))
            if (info.total <= 0) return@Canvas
            val total = info.total.toFloat()
            val ow = w * info.other / total
            val aw = w * info.ark / total
            val pw = w * pending.coerceAtMost(info.free) / total
            drawRoundRect(t.c(t.muted).copy(alpha = 0.55f), size = Size((ow + aw + pw).coerceAtMost(w), h), cornerRadius = CornerRadius(h / 2))
            drawRect(t.c(t.accent), topLeft = Offset(ow, 0f), size = Size(aw.coerceAtLeast(if (info.ark > 0) 3f else 0f), h))
            if (pw > 0) drawRect(t.c(t.accent).copy(alpha = 0.35f), topLeft = Offset(ow + aw, 0f), size = Size(pw.coerceAtMost(w - ow - aw), h))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Legend(t.c(t.accent), "Oarlock ${humanBytes(info.ark)}")
            if (pending > 0) Legend(t.c(t.accent).copy(alpha = 0.35f), "Queued ${humanBytes(pending)}")
            Legend(t.c(t.muted).copy(alpha = 0.55f), "Other apps & files ${humanBytes(info.other)}")
        }
        if (pending > info.free - Ark.storage.reserve && pending > 0) {
            Spacer(Modifier.height(6.dp))
            Text("Queued downloads won't all fit. Free up space or remove some from the queue.", style = MaterialTheme.typography.bodySmall, color = t.c(t.danger))
        }
        if (showManage) {
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS)) } },
                modifier = Modifier.align(Alignment.End)) { Text("Free up space on phone", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = LocalArk.current.c(LocalArk.current.muted))
    }
}

fun eta(remaining: Long, bps: Long): String {
    if (bps <= 0 || remaining <= 0) return ""
    val s = remaining / bps
    return when {
        s < 60 -> "${s}s left"
        s < 3600 -> "${s / 60} min left"
        s < 86400 -> "${s / 3600} h ${(s % 3600) / 60} min left"
        else -> "${s / 86400} d ${(s % 86400) / 3600} h left"
    }
}

/** "Oct 2026" for an ISO date, or null if unknown. */
fun monthYear(date: String?): String? {
    if (date.isNullOrBlank() || date.length < 7) return null
    val m = date.substring(5, 7).toIntOrNull() ?: return null
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${names.getOrElse(m - 1) { "?" }} ${date.take(4)}"
}

/** True when content is older than about 18 months. */
fun isStale(date: String?): Boolean {
    if (date.isNullOrBlank() || date.length < 7) return false
    val y = date.take(4).toIntOrNull() ?: return false
    val m = date.substring(5, 7).toIntOrNull() ?: return false
    val now = java.util.Calendar.getInstance()
    val months = (now.get(java.util.Calendar.YEAR) - y) * 12 + (now.get(java.util.Calendar.MONTH) + 1 - m)
    return months > 18
}

/** Date line for a pack: when its content was published, flagging old ones. Books are public domain and never go stale. */
@Composable
fun PackDate(date: String?, isBooks: Boolean, installedFrom: String? = null) {
    val t = LocalArk.current
    val d = installedFrom?.takeIf { it.isNotBlank() } ?: date
    val text = when {
        isBooks -> "Public domain" + (monthYear(d)?.let { " · list checked $it" } ?: "")
        monthYear(d) == null -> "Date unknown"
        installedFrom != null -> "Your copy: ${monthYear(d)}"
        else -> "Updated ${monthYear(d)}"
    }
    val stale = !isBooks && isStale(d)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
        if (stale) {
            Spacer(Modifier.width(8.dp))
            Text("OVER 18 MONTHS OLD", style = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = t.c(t.danger)),
                modifier = Modifier.border(1.dp, t.c(t.danger), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}
