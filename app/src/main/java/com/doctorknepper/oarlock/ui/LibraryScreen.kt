package com.doctorknepper.oarlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.core.humanBytes
import com.doctorknepper.oarlock.data.InstalledPack
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.data.PackState
import com.doctorknepper.oarlock.engine.DownloadService
import com.doctorknepper.oarlock.engine.Engine
import java.text.DateFormat
import java.util.Date

/** Everything on the phone: what each pack is, how much room it takes, and what to do with it. */
@Composable
fun LibraryScreen(nav: Nav) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val packs by Ark.store.packs.collectAsState()
    val engine by Ark.engine.state.collectAsState()
    val saved by Ark.settings.saved.collectAsState()
    val storage = remember(packs) { storageInfo() }
    var confirm by remember { mutableStateOf<InstalledPack?>(null) }
    var sortBySize by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("Library", nav::back) {
            TextButton(onClick = { sortBySize = !sortBySize }) { Text(if (sortBySize) "By type" else "By size") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Panel { StorageBar(storage) } }
            item {
                val vols = Ark.storage.volumes()
                if (vols.size > 1) Panel {
                    Label("Phone & SD card")
                    vols.forEach { v ->
                        val here = packs.values.filter { v.root.path.let { r -> it.dir.path.startsWith(r) } }
                        Text("${v.label}: ${here.size} packs · ${humanBytes(here.sumOf { it.bytes })} · ${humanBytes(v.free())} free", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Packs on an SD card go wherever the card goes: they're unavailable while it's out of the phone. Keep medical and survival packs on phone storage.",
                        style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                }
            }
            val missing = Ark.missingOnCard()
            if (missing.isNotEmpty()) item {
                Panel {
                    Label("On your SD card · not available", color = t.c(t.danger))
                    Spacer(Modifier.height(4.dp))
                    Text("These packs are stored on an SD card that isn't in the phone right now. Put the card back to use them.",
                        style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    missing.values.forEach { (title, bytes) -> Text("• $title · ${humanBytes(bytes)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (packs.isEmpty()) item {
                Panel {
                    Text("Your library is empty", style = MaterialTheme.typography.titleSmall)
                    Text("Download packs from the catalog, or hold Emergency Download on the home screen.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { nav.go("catalog/") }) { Text("Open catalog") }
                }
            }
            if (saved.isNotEmpty()) {
                item { Label("Saved pages") }
                items(saved, key = { "s-" + it.second }) { (title, url) ->
                    Row(Modifier.fillMaxWidth().clickable { nav.read(url) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bookmark, null, tint = t.c(t.accent))
                        Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { Ark.settings.toggleSaved(title, url) }) { Icon(Icons.Filled.Close, "Remove", tint = t.c(t.muted)) }
                    }
                }
            }
            val list = packs.values.toList()
            val groups: Map<String, List<InstalledPack>> = if (sortBySize) mapOf("Largest first" to list.sortedByDescending { it.bytes })
            else list.groupBy { categoryOf(it) }.toSortedMap()
            groups.forEach { (cat, ps) ->
                item(key = "c-$cat") { Spacer(Modifier.height(4.dp)); Label("$cat · ${humanBytes(ps.sumOf { it.bytes })}") }
                items(ps, key = { it.id }) { p -> InstalledCard(p, nav, engine.queue.any { it.packId == p.id }, onDelete = { confirm = p }, onResume = {
                    Ark.settings.pausedByUser = false
                    if (Ark.catalog[p.id] != null) Ark.engine.enqueue(listOf(p.id)) else Ark.engine.defOf(com.doctorknepper.oarlock.engine.Job(p.id, false))?.let { Ark.engine.enqueueCustom(it) }
                    DownloadService.start(ctx)
                }) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${p.title}?") },
            text = { Text("This frees ${humanBytes(p.bytes)}. You'd need internet to download it again.") },
            confirmButton = { TextButton(onClick = { confirm = null; Ark.deletePack(p.id) }) { Text("Delete", color = t.c(t.danger)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
            containerColor = t.c(t.surface),
        )
    }
}

private fun categoryOf(p: InstalledPack): String = Ark.catalog[p.id]?.category ?: when (p.kind) {
    PackKind.CUSTOM_MAP, PackKind.MAP -> "Maps"
    PackKind.BOOKS, PackKind.SINGLE_BOOK, PackKind.GUTENBERG -> "Books"
    else -> "Other"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledCard(p: InstalledPack, nav: Nav, queued: Boolean, onDelete: () -> Unit, onResume: () -> Unit) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val engine by Ark.engine.state.collectAsState()
    val note = engine.notes[p.id]
    val onCard = Ark.storage.isRemovable(p.dir)
    val otherVol = Ark.storage.volumes().firstOrNull { !p.dir.path.startsWith(it.root.path) }
    val damaged = p.info.optBoolean("damaged")
    val optimizeLeft = p.kind == PackKind.WIKIPEDIA && p.info.optString("format") != "condensed" && p.file("articles.arkwiki").exists()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                val detail = buildList {
                    add(humanBytes(p.bytes))
                    if (p.state == PackState.PARTIAL) add(if (queued) "downloading" else "incomplete")
                    if (p.kind == PackKind.BOOKS) p.info.optJSONArray("items")?.let { add("${it.length()} books") }
                    if (p.kind == PackKind.WIKIPEDIA && p.info.optString("format") == "condensed") add("optimized")
                }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.labelSmall, color = if (p.state == PackState.PARTIAL) t.c(t.danger) else t.c(t.muted))
                if (p.state == PackState.INSTALLED) PackDate(Ark.catalog[p.id]?.date, p.kind == PackKind.BOOKS || p.kind == PackKind.GUTENBERG, p.info.optString("sourceDate"))
            }
            if (onCard) Text("SD CARD", style = MaterialTheme.typography.labelSmall, color = t.c(t.accent),
                modifier = Modifier.border(1.dp, t.c(t.accent), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        val verified = p.info.optLong("verifiedAt")
        if (p.state == PackState.INSTALLED) {
            Text(
                when {
                    damaged -> "Failed its last check. Delete it and download again while you have internet."
                    verified > 0 -> "Checked ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(verified))} · ${p.info.optString("verifiedBy")}"
                    else -> "Not checked yet"
                },
                style = MaterialTheme.typography.labelSmall, color = if (damaged) t.c(t.danger) else t.c(t.muted),
            )
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = if (note.startsWith("Damaged") || note.startsWith("Failed") || note.startsWith("Skipped")) t.c(t.danger) else t.c(t.accent))
        if (optimizeLeft && !queued) {
            Spacer(Modifier.height(6.dp))
            Text("An Optimize run was interrupted and its partial copy is using ${humanBytes(Ark.storage.dirSize(p.file("articles.arkwiki")) + Ark.storage.dirSize(p.file("articles.arkwiki.work")))}.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.danger))
            Row {
                TextButton(onClick = { Ark.settings.pausedByUser = false; Ark.engine.enqueueOptimize(p.id); DownloadService.start(ctx) }) { Text("Resume Optimize") }
                TextButton(onClick = { p.file("articles.arkwiki").delete(); p.file("articles.arkwiki.work").deleteRecursively(); p.file("titles-condensed.idx").delete(); Ark.store.refresh() }) { Text("Discard copy", color = t.c(t.danger)) }
            }
        } else if (p.kind == PackKind.WIKIPEDIA && p.state == PackState.INSTALLED && p.info.optString("format") != "condensed") {
            Spacer(Modifier.height(8.dp))
            Text("Optimize rewrites the articles without references and markup clutter and in stronger compression, saving about 40%. " +
                "It takes a few hours and needs room for a second copy while it runs; leave the phone charging.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (p.state == PackState.INSTALLED) {
                Button(onClick = { open(nav, p.id, if (p.id == Engine.MY_BOOKS) PackKind.BOOKS else p.kind) },
                    colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Open") }
                if (!queued) OutlinedButton(onClick = { Ark.settings.pausedByUser = false; Ark.engine.enqueueVerify(p.id); DownloadService.start(ctx) }) { Text("Verify") }
                if (p.kind == PackKind.WIKIPEDIA && p.info.optString("format") != "condensed" && !queued && !optimizeLeft)
                    OutlinedButton(onClick = { Ark.settings.pausedByUser = false; Ark.engine.enqueueOptimize(p.id); DownloadService.start(ctx) }) { Text("Optimize") }
                if (otherVol != null && !queued)
                    OutlinedButton(onClick = { Ark.settings.pausedByUser = false; Ark.engine.enqueueMove(p.id, otherVol.root.path); DownloadService.start(ctx) }) {
                        Text(if (otherVol.removable) "Move to SD card" else "Move to phone")
                    }
            } else if (!queued && (Ark.catalog[p.id] != null || p.info.has("def"))) {
                Button(onClick = onResume, colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Resume") }
            }
            if (p.kind == PackKind.WIKIPEDIA && p.state == PackState.PARTIAL && Ark.wiki.has()) {
                OutlinedButton(onClick = { nav.go("search") }) { Text("Search titles") }
            }
            TextButton(onClick = onDelete) { Text("Delete", color = t.c(t.danger)) }
        }
    }
}
