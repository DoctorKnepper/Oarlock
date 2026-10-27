package com.doctorknepper.ark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doctorknepper.ark.Ark
import com.doctorknepper.ark.BuildConfig
import com.doctorknepper.ark.core.humanBytes

@Composable
fun SettingsScreen(nav: Nav) {
    val t = LocalArk.current
    val themeId by Ark.settings.theme.collectAsState()
    val fontScale by Ark.settings.fontScale.collectAsState()
    val wifiOnly by Ark.settings.wifiOnlyLarge.collectAsState()
    val onlineImages by Ark.settings.onlineImages.collectAsState()
    val cacheImages by Ark.settings.cacheImages.collectAsState()
    val keepAwake by Ark.settings.keepAwake.collectAsState()
    var storagePath by remember { mutableStateOf(Ark.storage.current().root.path) }
    var cacheSize by remember { mutableLongStateOf(Ark.storage.dirSize(Ark.imageCache)) }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("Settings", nav::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Label("Color scheme")
            Spacer(Modifier.height(8.dp))
            ArkTheme.all.forEach { th ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Ark.settings.setTheme(th.id) }
                        .background(if (th.id == themeId) t.c(t.surface) else t.c(t.bg)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(th)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(th.name, style = MaterialTheme.typography.bodyLarge)
                        if (th.note.isNotEmpty()) Text(th.note, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                    if (th.id == themeId) Icon(Icons.Filled.Check, "Selected", tint = t.c(t.accent))
                }
            }

            Spacer(Modifier.height(20.dp))
            Label("Reading text size · ${(fontScale * 100).toInt()}%")
            Slider(fontScale, { Ark.settings.setFontScale((it * 20).toInt() / 20f) }, valueRange = 0.7f..1.8f,
                colors = SliderDefaults.colors(thumbColor = t.c(t.accent), activeTrackColor = t.c(t.accent), inactiveTrackColor = t.c(t.line)))
            Text("The quick brown fox reads by lantern light.", fontSize = (17 * fontScale).sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(4.dp))
            Toggle("Keep screen on while reading", "Useful when following instructions step by step.", keepAwake) { Ark.settings.setKeepAwake(it) }

            Spacer(Modifier.height(20.dp))
            Label("Storage")
            Spacer(Modifier.height(6.dp))
            Panel { StorageBar(storageInfo()) }
            Spacer(Modifier.height(8.dp))
            val vols = Ark.storage.volumes()
            if (vols.size > 1) {
                Text("Save new downloads to:", style = MaterialTheme.typography.bodyMedium)
                vols.forEach { v ->
                    Row(Modifier.fillMaxWidth().selectable(storagePath == v.root.path) { Ark.settings.storagePath = v.root.path; storagePath = v.root.path },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(storagePath == v.root.path, { Ark.settings.storagePath = v.root.path; storagePath = v.root.path },
                            colors = RadioButtonDefaults.colors(selectedColor = t.c(t.accent)))
                        Column {
                            Text(v.label)
                            Text("${humanBytes(v.free())} free of ${humanBytes(v.total())}", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                        }
                    }
                }
                Text("Packs already downloaded stay where they are.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            } else {
                Text("Insert an SD card to store large packs like Wikipedia and maps on it.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            }
            Text("Ark always leaves ${humanBytes(Ark.storage.reserve)} free so your phone keeps working.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))

            Spacer(Modifier.height(20.dp))
            Label("Downloads")
            Toggle("Large packs on Wi-Fi only", "Packs over 1 GB wait for Wi-Fi. DEFCON 1 ignores this and uses any connection.", wifiOnly) { Ark.settings.setWifiOnlyLarge(it) }

            Spacer(Modifier.height(20.dp))
            Label("Wikipedia pictures")
            Text("Full-text Wikipedia has no pictures. Ark fills them in from any downloaded image packs (WikiMed, Best of Wikipedia, topic packs).",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            Toggle("Load other pictures when online", "Fetches missing pictures from Wikimedia Commons while you have internet.", onlineImages) { Ark.settings.setOnlineImages(it) }
            Toggle("Keep pictures I've viewed", "Saves those pictures so they still show offline later.", cacheImages) { Ark.settings.setCacheImages(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved pictures: ${humanBytes(cacheSize)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                OutlinedButton(onClick = { Ark.imageCache.deleteRecursively(); Ark.imageCache.mkdirs(); cacheSize = 0 }, enabled = cacheSize > 0) { Text("Clear") }
            }

            Spacer(Modifier.height(20.dp))
            Label("About")
            Spacer(Modifier.height(6.dp))
            Text("Ark ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text("An offline library for when the internet is gone. Content comes from Wikipedia and Wikimedia projects (CC BY-SA), " +
                "Kiwix and openZIM, OpenStreetMap via Protomaps (ODbL), Project Gutenberg and the Internet Archive (public domain). " +
                "The Field Guide summarizes public guidance from the Red Cross, CDC, FEMA and the U.S. Army survival manual; it is not a substitute for training or professional care.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalArk.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = t.c(t.onAccent), checkedTrackColor = t.c(t.accent),
            uncheckedTrackColor = t.c(t.surface2), uncheckedThumbColor = t.c(t.muted), uncheckedBorderColor = t.c(t.line)))
    }
}

@Composable
private fun Swatch(th: ArkTheme) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(th.c(th.bg)).border(1.dp, th.c(th.line), CircleShape), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(th.c(th.accent)))
            Box(Modifier.size(10.dp).clip(CircleShape).background(th.c(th.text)))
        }
    }
}
