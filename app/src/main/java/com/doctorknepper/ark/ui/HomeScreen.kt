package com.doctorknepper.ark.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doctorknepper.ark.Ark
import com.doctorknepper.ark.core.humanBytes
import com.doctorknepper.ark.data.PackDef
import com.doctorknepper.ark.engine.DownloadService
import kotlinx.coroutines.launch

object Defcon {
    fun list(): List<PackDef> = (Ark.settings.defcon.value ?: Ark.catalog.defaultDefcon).mapNotNull { Ark.catalog[it] }

    data class Plan(val fits: List<PackDef>, val skipped: List<PackDef>, val installed: List<PackDef>, val totalBytes: Long, val free: Long)

    fun plan(): Plan {
        val all = list()
        val installed = all.filter { Ark.store.isInstalled(it.id) }
        var room = Ark.storage.current().free() - Ark.storage.reserve
        val fits = ArrayList<PackDef>(); val skipped = ArrayList<PackDef>()
        for (p in all - installed.toSet()) {
            val need = p.size + p.extraSpace - (Ark.store[p.id]?.bytes ?: 0)
            if (need <= room) { fits.add(p); room -= need } else skipped.add(p)
        }
        return Plan(fits, skipped, installed, fits.sumOf { it.size }, Ark.storage.current().free())
    }

    fun start(context: android.content.Context) {
        val p = plan()
        Ark.engine.enqueue(p.fits.map { it.id }, defcon = true)
        DownloadService.start(context)
    }
}

@Composable
fun HomeScreen(nav: Nav) {
    val t = LocalArk.current
    val packs by Ark.store.packs.collectAsState()
    val engine by Ark.engine.state.collectAsState()
    val defconIds by Ark.settings.defcon.collectAsState()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<Defcon.Plan?>(null) }
    val defList = remember(defconIds, packs) { Defcon.list() }
    val ready = defList.count { Ark.store.isInstalled(it.id) }
    val storage = remember(packs, engine.running) { storageInfo() }
    val pending = engine.queue.sumOf { j -> (Ark.engine.defOf(j)?.size ?: 0) - (Ark.store[j.packId]?.bytes ?: 0) }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize().background(t.c(t.bg)).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ARK", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, letterSpacing = 6.sp, fontWeight = FontWeight.Bold), color = t.c(t.accent))
                Label("Offline library · works without internet")
            }
            IconButton(onClick = { nav.go("settings") }) { Icon(Icons.Filled.Settings, "Settings", tint = t.c(t.accent)) }
        }
        Spacer(Modifier.height(14.dp))
        // search
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(t.c(t.surface)).border(1.dp, t.c(t.line), RoundedCornerShape(28.dp))
                .clickable { nav.go("search") }.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = t.c(t.accent))
            Spacer(Modifier.width(12.dp))
            Text("Search everything offline", color = t.c(t.muted))
        }
        Spacer(Modifier.height(12.dp))
        val last by Ark.settings.lastPage.collectAsState()
        last?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (title, url) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.read(url) }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Book, null, tint = t.c(t.muted), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Continue: $title", style = MaterialTheme.typography.bodyMedium, color = t.c(t.muted), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (engine.running || engine.queue.isNotEmpty()) {
            Panel(onClick = { nav.go("downloads") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(
                        when { !engine.running -> "Downloads paused"; engine.defconActive -> "DEFCON 1 active"; else -> "Downloading" },
                        color = if (engine.defconActive && engine.running) t.c(t.danger) else t.c(t.accent),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${engine.queue.size} in queue", style = MaterialTheme.typography.labelMedium, color = t.c(t.muted))
                }
                Spacer(Modifier.height(6.dp))
                Text(if (engine.running) engine.currentTitle.ifEmpty { "Starting…" } else "Tap to resume", style = MaterialTheme.typography.titleMedium)
                Text(engine.step + if (engine.bytesPerSec > 0) " · ${humanBytes(engine.bytesPerSec)}/s" else "", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                Spacer(Modifier.height(8.dp))
                if (engine.running) {
                    if (engine.total > 0) LinearProgressIndicator(progress = { engine.fraction }, modifier = Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.line))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.line))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Readiness + DEFCON 1
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Readiness")
                    Text("$ready of ${defList.size} essentials ready", style = MaterialTheme.typography.titleMedium)
                    val next = defList.firstOrNull { !Ark.store.isInstalled(it.id) }
                    Text(if (next == null) "Everything in your DEFCON 1 list is on this phone." else "Next up: ${next.title}",
                        style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                }
                TextButton(onClick = { nav.go("defcon") }) { Text("Edit list") }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { if (defList.isEmpty()) 0f else ready.toFloat() / defList.size }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = t.c(t.accent), trackColor = t.c(t.line), strokeCap = StrokeCap.Round)
            Spacer(Modifier.height(18.dp))
            DefconButton(enabled = ready < defList.size) { confirm = Defcon.plan() }
            Spacer(Modifier.height(6.dp))
            Text(
                if (ready < defList.size) "Hold for 2 seconds to download everything on your list, most important first." else "All essentials downloaded.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted), modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(12.dp))
        Panel { StorageBar(storage, pending) }
        Spacer(Modifier.height(16.dp))

        Label("Open")
        Spacer(Modifier.height(8.dp))
        val tiles = listOf(
            Tile("Field Guide", "First aid & survival", Icons.Filled.MedicalServices) { nav.go("field") },
            Tile("Wikipedia", if (Ark.wiki.has()) "Read & search" else "Not downloaded", Icons.Filled.Public) { if (Ark.wiki.has()) nav.go("search") else nav.go("catalog/Encyclopedias") },
            Tile("Library", "${packs.size} packs", Icons.Filled.Inventory2) { nav.go("library") },
            Tile("Books", "Scripture, science, classics", Icons.Filled.MenuBook) { nav.go("books") },
            Tile("Maps", "Offline maps & GPS", Icons.Filled.Map) { nav.go("map") },
            Tile("Download", "Catalog of packs", Icons.Filled.CloudDownload) { nav.go("catalog/") },
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> TileView(tile, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (Ark.wiki.has()) {
            TextButton(onClick = { Ark.wiki.random()?.let { (p, title) -> nav.read(Ark.wiki.url(p, title)) } }) {
                Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Random article")
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    confirm?.let { plan ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Start DEFCON 1?", color = t.c(t.danger)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Ark will download ${plan.fits.size} packs (${humanBytes(plan.totalBytes)}) in this order, keeping going with the screen off. " +
                        "It will use mobile data if Wi-Fi isn't available.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    plan.fits.forEachIndexed { i, p ->
                        Row { Text("${i + 1}. ${p.title}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(humanBytes(p.size), style = MaterialTheme.typography.bodySmall, color = t.c(t.muted)) }
                    }
                    if (plan.skipped.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Won't fit (${humanBytes(plan.free)} free):", style = MaterialTheme.typography.labelLarge, color = t.c(t.danger))
                        plan.skipped.forEach { p -> Text("• ${p.title} · ${humanBytes(p.size)}", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted)) }
                        Text("Free up space or edit the list to include them.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = null; Defcon.start(ctx); nav.go("downloads") }, enabled = plan.fits.isNotEmpty()) { Text("Download now") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            containerColor = t.c(t.surface),
        )
    }
}

class Tile(val title: String, val sub: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun TileView(tile: Tile, modifier: Modifier) {
    val t = LocalArk.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(t.c(t.surface)).border(1.dp, t.c(t.line), RoundedCornerShape(14.dp))
            .clickable(onClick = tile.onClick).padding(14.dp)
    ) {
        Icon(tile.icon, null, tint = t.c(t.accent), modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(tile.title, style = MaterialTheme.typography.titleSmall)
        Text(tile.sub, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted), maxLines = 1)
    }
}

/** Large hold-to-activate button: a ring fills while held; letting go early cancels. */
@Composable
fun DefconButton(enabled: Boolean, onActivate: () -> Unit) {
    val t = LocalArk.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val red = t.c(t.danger)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(176.dp).pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val job = scope.launch {
                        progress.animateTo(1f, tween(2000, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onActivate()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(250)) }
                })
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 10.dp.toPx()
                drawCircle(red.copy(alpha = if (enabled) 0.18f else 0.08f), radius = size.minDimension / 2 - stroke / 2)
                drawCircle(red.copy(alpha = if (enabled) 0.9f else 0.3f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(2.dp.toPx()))
                drawArc(red, -90f, 360f * progress.value, false, topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DEFCON", color = red.copy(alpha = if (enabled) 1f else 0.4f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, fontSize = 16.sp)
                Text("1", color = red.copy(alpha = if (enabled) 1f else 0.4f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 56.sp)
                Text(if (enabled) "HOLD" else "READY", color = red.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 3.sp)
            }
        }
    }
}
