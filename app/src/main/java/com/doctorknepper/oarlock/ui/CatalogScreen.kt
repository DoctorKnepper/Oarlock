package com.doctorknepper.oarlock.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.core.humanBytes
import com.doctorknepper.oarlock.data.PackDef
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.data.PackState
import com.doctorknepper.oarlock.engine.DownloadService
import com.doctorknepper.oarlock.data.Plans

@Composable
fun CatalogScreen(nav: Nav, startCategory: String?) {
    val t = LocalArk.current
    val cats = Ark.catalog.categories
    var tab by remember { mutableIntStateOf(cats.indexOf(startCategory).coerceAtLeast(0)) }
    val packs by Ark.store.packs.collectAsState()
    val engine by Ark.engine.state.collectAsState()
    val storage = remember(packs, engine.queue.size) { storageInfo() }
    val pending = engine.queue.sumOf { j -> (Ark.engine.defOf(j)?.size ?: 0) - (Ark.store[j.packId]?.bytes ?: 0) }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("Download packs", nav::back) { TextButton(onClick = { nav.go("downloads") }) { Text("Queue (${engine.queue.size})") } }
        Column(Modifier.padding(horizontal = 16.dp)) {
            StorageBar(storage, pending)
            Spacer(Modifier.height(8.dp))
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = t.c(t.bg), contentColor = t.c(t.accent)) {
            cats.forEachIndexed { i, c -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(c) }) }
        }
        // Small curated book collections first, then the Gutenberg catalog, then whole (large) libraries.
        val list = Ark.catalog.packs.filter { it.category == cats[tab] }.sortedBy {
            when (it.kind) { PackKind.BOOKS -> 0; PackKind.GUTENBERG -> 1; else -> 2 }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(categoryBlurb(cats[tab]), style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            }
            items(list, key = { it.id }) { def -> PackCard(def, nav, storage.free - pending) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

private fun categoryBlurb(c: String) = when (c) {
    "Medical" -> "Medical references for when no doctor is reachable. WikEM and WikiMed are the most useful starting points."
    "Survival" -> "Manuals and guides for water, food, shelter, knots and recovering after a disaster."
    "Maps" -> "Street maps that work with GPS and no signal. Pick one North America detail level; the World Overview fills in everywhere else. You can also download a detailed map of any area from the Maps screen."
    "Encyclopedias" -> "Wikipedia and other references. The full Wikipedia is large; Simple English Wikipedia is a light alternative."
    "Image Packs" -> "Topic collections from Wikipedia with pictures. Their images also appear inside matching full-text Wikipedia articles."
    "Repair & Skills" -> "Repair guides, appropriate technology and practical Q&A."
    "Books" -> "About 250 hand-picked public-domain books in 13 collections: scripture of the world's ten largest religions plus Unitarian Universalist heritage, science, philosophy, psychology and economics, practical skills, novels, poetry and drama, mystery, science fiction and horror, myth, history, memoir and travel, and children's classics. The Gutenberg catalog lets you browse 70,000+ more by genre."
    "Education" -> "Open textbooks and interactive science."
    else -> ""
}

@Composable
fun PackCard(def: PackDef, nav: Nav, freeAfterQueue: Long) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val packs by Ark.store.packs.collectAsState()
    val engine by Ark.engine.state.collectAsState()
    val installed = packs[def.id]
    val queued = engine.queue.any { it.packId == def.id }
    val current = engine.current == def.id
    val note = engine.notes[def.id]
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val have = installed?.bytes ?: 0
    // Maps of the same area: a more detailed one already here covers this one; a more detailed one replaces less detailed ones.
    val coveredBy = if (installed == null) Plans.covered(def) else null
    val replaces = def.group?.let { g -> packs.values.filter { it.id != def.id && Ark.engine.groupOf(it) == g && Ark.engine.maxZoomOf(it) < def.maxZoom } } ?: emptyList()
    val need = (def.size + def.extraSpace - have).coerceAtLeast(0)
    val fits = need <= freeAfterQueue - Ark.storage.reserve

    Panel(Modifier.animateContentSize(), onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(def.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(humanBytes(def.size), style = MaterialTheme.typography.labelMedium, color = t.c(t.accent))
                    if (def.hasImages) { Spacer(Modifier.width(8.dp)); Label("images") }
                    if (def.id in Plans.coreIds()) { Spacer(Modifier.width(8.dp)); Label("DEFCON core", color = t.c(t.danger)) }
                    else if (def.id in Plans.fullExtraIds()) { Spacer(Modifier.width(8.dp)); Label("DEFCON full", color = t.c(t.danger)) }
                }
                PackDate(def.date, def.kind == PackKind.BOOKS || def.kind == PackKind.GUTENBERG,
                    installed?.takeIf { it.state == PackState.INSTALLED }?.info?.optString("sourceDate"))
            }
            when {
                installed?.state == PackState.INSTALLED -> Icon(Icons.Filled.CheckCircle, "Installed", tint = t.c(t.accent))
                current || queued -> Icon(Icons.Filled.Schedule, "Queued", tint = t.c(t.muted))
                note != null -> Icon(Icons.Filled.ErrorOutline, "Needs attention", tint = t.c(t.danger))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(def.desc, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted), maxLines = if (expanded) 20 else 2)
        if (note != null) { Spacer(Modifier.height(6.dp)); Text(note, style = MaterialTheme.typography.bodySmall, color = if (note.startsWith("Verified") || note.startsWith("Replaced") || note.startsWith("Moved")) t.c(t.muted) else t.c(t.danger)) }
        if (coveredBy != null) { Spacer(Modifier.height(6.dp)); Text("Already covered by ${coveredBy.title}, which has more detail.", style = MaterialTheme.typography.bodySmall, color = t.c(t.accent)) }
        if (installed?.state != PackState.INSTALLED && replaces.isNotEmpty() && coveredBy == null) {
            Spacer(Modifier.height(6.dp))
            Text("Upgrades ${replaces.joinToString { it.title }}: the old map is deleted once this one is downloaded and checked, freeing ${humanBytes(replaces.sumOf { it.bytes })}.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.accent))
        }
        if (current) {
            Spacer(Modifier.height(8.dp))
            Text(engine.step, style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
            Spacer(Modifier.height(4.dp))
            if (engine.total > 0) LinearProgressIndicator(progress = { engine.fraction }, Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.line))
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.line))
        }
        if (expanded && def.kind == PackKind.BOOKS) {
            Spacer(Modifier.height(8.dp))
            def.books.groupBy { it.genre }.forEach { (g, items) ->
                Label(g)
                items.forEach { Text("• ${it.title}" + if (it.author.isNotBlank()) " — ${it.author}" else "", style = MaterialTheme.typography.bodySmall, color = t.c(t.text)) }
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                installed?.state == PackState.INSTALLED -> {
                    Button(onClick = { open(nav, def.id, def.kind) }, colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Open") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
                current || queued -> OutlinedButton(onClick = { Ark.engine.remove(def.id) }) { Text(if (current) "Stop" else "Remove from queue") }
                coveredBy != null -> {}
                else -> {
                    Button(
                        onClick = {
                            // only one map per area at a time: drop less detailed versions waiting in the queue
                            def.group?.let { g -> engine.queue.filter { j -> Ark.catalog[j.packId]?.group == g }.forEach { Ark.engine.remove(it.packId) } }
                            Ark.settings.pausedByUser = false
                            Ark.engine.enqueue(listOf(def.id)); DownloadService.start(ctx)
                        }, enabled = fits,
                        colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent)),
                    ) { Text(if (installed != null) "Resume" else "Download") }
                    if (installed != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("Discard") }
                }
            }
            Spacer(Modifier.weight(1f))
            if (installed?.state != PackState.INSTALLED) {
                Text(
                    if (fits) "${humanBytes(freeAfterQueue - need)} left after" else "Needs ${humanBytes(need)} free",
                    style = MaterialTheme.typography.labelSmall, color = if (fits) t.c(t.muted) else t.c(t.danger),
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${def.title}?") },
            text = { Text("This frees ${humanBytes(have)}. You'd need internet to download it again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; Ark.deletePack(def.id) }) { Text("Delete", color = t.c(t.danger)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
            containerColor = t.c(t.surface),
        )
    }
}

fun open(nav: Nav, id: String, kind: PackKind) {
    when (kind) {
        PackKind.ZIM -> nav.read("https://ark.local/zim/$id/")
        PackKind.WIKIPEDIA -> nav.go("search")
        PackKind.MAP, PackKind.CUSTOM_MAP -> nav.go("map")
        PackKind.BOOKS, PackKind.SINGLE_BOOK, PackKind.GUTENBERG -> nav.go("books")
    }
}
