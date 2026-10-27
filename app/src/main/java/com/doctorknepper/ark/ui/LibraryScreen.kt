package com.doctorknepper.ark.ui

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
import com.doctorknepper.ark.Ark
import com.doctorknepper.ark.core.humanBytes
import com.doctorknepper.ark.data.InstalledPack
import com.doctorknepper.ark.data.PackKind
import com.doctorknepper.ark.data.PackState
import com.doctorknepper.ark.engine.DownloadService
import com.doctorknepper.ark.engine.Engine
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
            if (packs.isEmpty()) item {
                Panel {
                    Text("Your library is empty", style = MaterialTheme.typography.titleSmall)
                    Text("Download packs from the catalog, or hold DEFCON 1 on the home screen.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
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
                    Ark.engine.enqueue(listOf(p.id)); DownloadService.start(ctx)
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

@Composable
private fun InstalledCard(p: InstalledPack, nav: Nav, queued: Boolean, onDelete: () -> Unit, onResume: () -> Unit) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                val detail = buildList {
                    add(humanBytes(p.bytes))
                    if (p.state == PackState.PARTIAL) add(if (queued) "downloading" else "incomplete")
                    if (p.installedAt > 0) add(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.installedAt)))
                    if (p.kind == PackKind.BOOKS) p.info.optJSONArray("items")?.let { add("${it.length()} books") }
                    if (p.kind == PackKind.WIKIPEDIA && p.info.optString("format") == "condensed") add("optimized")
                }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.labelSmall, color = if (p.state == PackState.PARTIAL) t.c(t.danger) else t.c(t.muted))
            }
        }
        if (p.kind == PackKind.WIKIPEDIA && p.state == PackState.INSTALLED && p.info.optString("format") != "condensed") {
            Spacer(Modifier.height(8.dp))
            Text("Optimize rewrites the articles without references and markup clutter and in stronger compression, saving about 35%. " +
                "It takes a few hours and needs room for a second copy while it runs; leave the phone charging.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.state == PackState.INSTALLED) {
                Button(onClick = { open(nav, p.id, if (p.id == Engine.MY_BOOKS) PackKind.BOOKS else p.kind) },
                    colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Open") }
                if (p.kind == PackKind.WIKIPEDIA && p.info.optString("format") != "condensed" && !queued)
                    OutlinedButton(onClick = { Ark.engine.enqueueOptimize(p.id); DownloadService.start(ctx) }) { Text("Optimize") }
            } else if (!queued && Ark.catalog[p.id] != null) {
                Button(onClick = onResume, colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Resume") }
            }
            if (p.kind == PackKind.WIKIPEDIA && p.state == PackState.PARTIAL && Ark.wiki.has()) {
                OutlinedButton(onClick = { nav.go("search") }) { Text("Search titles") }
            }
            Row(Modifier.weight(1f)) {}
            TextButton(onClick = onDelete) { Text("Delete", color = t.c(t.danger)) }
        }
    }
}
