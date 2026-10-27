package com.doctorknepper.oarlock.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.doctorknepper.oarlock.data.Plans
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.core.humanBytes
import com.doctorknepper.oarlock.engine.DownloadService

@Composable
fun DownloadsScreen(nav: Nav) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val s by Ark.engine.state.collectAsState()
    val packs by Ark.store.packs.collectAsState()
    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("Downloads", nav::back) { TextButton(onClick = { nav.go("catalog/") }) { Text("Add packs") } }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                if (s.running) {
                    Panel {
                        Label(if (s.defconActive) "DEFCON 1 · in progress" else "In progress", color = if (s.defconActive) t.c(t.danger) else t.c(t.accent))
                        Spacer(Modifier.height(6.dp))
                        Text(s.currentTitle, style = MaterialTheme.typography.titleMedium)
                        Text(s.step, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                        Spacer(Modifier.height(10.dp))
                        if (s.total > 0) LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.line))
                        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.line))
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text(if (s.total > 0) "${humanBytes(s.done)} of ${humanBytes(s.total)}" else "", style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
                            Spacer(Modifier.weight(1f))
                            Text(listOf(if (s.bytesPerSec > 0) "${humanBytes(s.bytesPerSec)}/s" else "", eta(s.total - s.done, s.bytesPerSec)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { Ark.settings.pausedByUser = true; DownloadService.stop(ctx) }) { Text("Pause all") }
                    }
                } else if (s.queue.isNotEmpty()) {
                    Panel {
                        Text("Downloads are paused", style = MaterialTheme.typography.titleMedium)
                        Text("${s.queue.size} pack(s) waiting. Partly downloaded files are kept and resume where they stopped.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { Ark.settings.pausedByUser = false; DownloadService.start(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Resume") }
                    }
                } else {
                    Panel {
                        Text("Nothing downloading", style = MaterialTheme.typography.titleMedium)
                        Text("Pick packs from the catalog, or hold DEFCON 1 on the home screen to get your essentials.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                }
            }
            if (s.queue.isNotEmpty()) item { Label("Queue · in order") }
            items(s.queue, key = { it.packId + it.action }) { job ->
                val def = Ark.engine.defOf(job)
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(when {
                                job.action == "optimize" -> "Optimize: "
                                job.action == "verify" -> "Verify: "
                                job.action.startsWith("move:") -> "Move: "
                                else -> ""
                            } + (def?.title ?: job.packId), style = MaterialTheme.typography.titleSmall)
                            val have = packs[job.packId]?.bytes ?: 0
                            Text(humanBytes(def?.size ?: 0) + if (have > 0 && job.action == "install") " · ${humanBytes(have)} done" else "",
                                style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
                        }
                        IconButton(onClick = { Ark.engine.move(job.packId, -1) }) { Icon(Icons.Filled.ArrowUpward, "Move up", tint = t.c(t.muted)) }
                        IconButton(onClick = { Ark.engine.move(job.packId, 1) }) { Icon(Icons.Filled.ArrowDownward, "Move down", tint = t.c(t.muted)) }
                        IconButton(onClick = { Ark.engine.remove(job.packId) }) { Icon(Icons.Filled.Close, "Remove", tint = t.c(t.danger)) }
                    }
                }
            }
            if (s.notes.isNotEmpty()) {
                item { Spacer(Modifier.height(6.dp)); Label("Needs attention") }
                items(s.notes.entries.toList(), key = { "note-" + it.key }) { (id, msg) ->
                    Panel {
                        Text(Ark.catalog[id]?.title ?: id, style = MaterialTheme.typography.titleSmall)
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = t.c(t.danger))
                        if (!Ark.store.isInstalled(id) && Ark.catalog[id] != null) {
                            TextButton(onClick = { Ark.settings.pausedByUser = false; Ark.engine.enqueue(listOf(id)); DownloadService.start(ctx) }) { Text("Try again") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

/** Choose which packs each DEFCON tier downloads and in what order. */
@Composable
fun DefconScreen(nav: Nav) {
    val t = LocalArk.current
    var tab by remember { mutableStateOf(0) }
    val core = remember { mutableStateListOf<String>().apply { addAll(Plans.coreIds()) } }
    val full = remember { mutableStateListOf<String>().apply { addAll(Plans.fullExtraIds()) } }
    val storage = remember { storageInfo() }
    fun save() { Ark.settings.setDefconCore(core.toList()); Ark.settings.setDefconFull(full.toList()) }
    val order = if (tab == 0) core else full
    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("DEFCON 1 lists", nav::back) {
            TextButton(onClick = { Ark.settings.resetDefcon(); core.clear(); core.addAll(Ark.catalog.defaultCore); full.clear(); full.addAll(Ark.catalog.defaultFullExtra) }) { Text("Reset") }
        }
        TabRow(selectedTabIndex = tab, containerColor = t.c(t.bg), contentColor = t.c(t.accent)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Core") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Full adds") })
        }
        val coreSize = core.sumOf { Ark.catalog[it]?.size ?: 0 }
        val fullSize = coreSize + full.sumOf { Ark.catalog[it]?.size ?: 0 }
        Column(Modifier.padding(16.dp)) {
            Text(if (tab == 0) "Core is the default when you hold DEFCON 1: the essentials, small enough for nearly any phone. Put what you'd need first at the top."
                else "Full downloads Core first, then these larger packs in this order.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            Spacer(Modifier.height(8.dp))
            Row {
                Text(if (tab == 0) "Core: ${core.size} packs · ${humanBytes(coreSize)}" else "Full: ${core.size + full.size} packs · ${humanBytes(fullSize)}",
                    style = MaterialTheme.typography.labelLarge, color = if ((if (tab == 0) coreSize else fullSize) > storage.free) t.c(t.danger) else t.c(t.accent))
                Spacer(Modifier.weight(1f))
                Text("${humanBytes(storage.free)} free", style = MaterialTheme.typography.labelLarge, color = t.c(t.muted))
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Label("In the list") }
            items(order.toList(), key = { "in-$tab-$it" }) { id ->
                val def = Ark.catalog[id] ?: return@items
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = { order.remove(id); save() }, colors = CheckboxDefaults.colors(checkedColor = t.c(t.accent), checkmarkColor = t.c(t.onAccent)))
                    Column(Modifier.weight(1f)) {
                        Text("${order.indexOf(id) + 1}. ${def.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(humanBytes(def.size) + if (Ark.store.isInstalled(id)) " · downloaded" else "", style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
                    }
                    IconButton(onClick = { val i = order.indexOf(id); if (i > 0) { order.removeAt(i); order.add(i - 1, id); save() } }) { Icon(Icons.Filled.ArrowUpward, "Up", tint = t.c(t.muted)) }
                    IconButton(onClick = { val i = order.indexOf(id); if (i < order.size - 1) { order.removeAt(i); order.add(i + 1, id); save() } }) { Icon(Icons.Filled.ArrowDownward, "Down", tint = t.c(t.muted)) }
                }
            }
            item { Spacer(Modifier.height(10.dp)); Label("Add to the list") }
            items(Ark.catalog.packs.filter { it.id !in core && it.id !in full }, key = { "out-$tab-${it.id}" }) { def ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = { order.add(def.id); save() })
                    Column(Modifier.weight(1f)) {
                        Text(def.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${def.category} · ${humanBytes(def.size)}", style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
