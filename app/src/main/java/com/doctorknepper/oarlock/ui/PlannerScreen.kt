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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.core.humanBytes
import com.doctorknepper.oarlock.data.Plans
import com.doctorknepper.oarlock.engine.DownloadService

/** "What fits on my phone": picks the most useful set of packs for the room you're willing to use. */
@Composable
fun PlannerScreen(nav: Nav) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val budgetMax = remember { Plans.budget().coerceAtLeast(0) }
    var fraction by remember { mutableFloatStateOf(0.8f) }
    val budget = (budgetMax * fraction).toLong()
    val plan = remember(budget) { Plans.suggest(budget) }
    val unchecked = remember { mutableStateListOf<String>() }
    val chosen = plan.filter { it.id !in unchecked }
    val total = chosen.sumOf { it.size }
    val storage = remember { storageInfo() }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("What fits on this phone", nav::back)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Panel { StorageBar(storage, showManage = true) } }
            item {
                Spacer(Modifier.height(8.dp))
                Label("Space to use · ${humanBytes(budget)} of ${humanBytes(budgetMax)} available")
                Slider(fraction, { fraction = it }, valueRange = 0.05f..1f,
                    colors = SliderDefaults.colors(thumbColor = t.c(t.accent), activeTrackColor = t.c(t.accent), inactiveTrackColor = t.c(t.line)))
                Text("Oarlock always leaves ${humanBytes(Ark.storage.reserve)} free for your phone. Slide to keep more room for photos and apps.",
                    style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            }
            item {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Suggested · ${chosen.size} packs · ${humanBytes(total)}", Modifier.weight(1f))
                }
                Text("Emergency Download Core comes first, then Full, then everything else by how useful it is in an emergency. Untick anything you don't want.",
                    style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            }
            if (plan.isEmpty()) item {
                Text(if (budgetMax <= 0) "There's no free space to plan with. Free up space on the phone first." else "Everything that fits is already on this phone or queued.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(plan, key = { it.id }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.id !in unchecked, { on -> if (on) unchecked.remove(p.id) else unchecked.add(p.id) },
                        colors = CheckboxDefaults.colors(checkedColor = t.c(t.accent), checkmarkColor = t.c(t.onAccent)))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyMedium)
                        val tag = when (p.id) { in Plans.coreIds() -> "Emergency Core"; in Plans.fullExtraIds() -> "Emergency Full"; else -> p.category }
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = t.c(t.muted))
                    }
                    Text(humanBytes(p.size), style = MaterialTheme.typography.labelMedium, color = t.c(t.muted))
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        Ark.settings.pausedByUser = false
                        Ark.engine.enqueue(chosen.map { it.id })
                        DownloadService.start(ctx); nav.go("downloads")
                    },
                    enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent)),
                ) { Text("Download ${chosen.size} packs (${humanBytes(total)})") }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
