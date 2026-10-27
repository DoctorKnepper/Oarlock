package com.doctorknepper.ark.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.doctorknepper.ark.Ark
import com.doctorknepper.ark.core.Bbox
import com.doctorknepper.ark.core.Pm
import com.doctorknepper.ark.core.humanBytes
import com.doctorknepper.ark.data.Catalog
import com.doctorknepper.ark.engine.DownloadService

/** Rough size of a map extract: tiles in the area at each zoom times the average tile size measured for North America. */
object MapEstimate {
    // Average bytes per tile per zoom, measured on the Protomaps planet for North America (2026-10).
    private val avg = longArrayOf(100_000, 100_000, 50_000, 44_000, 27_000, 19_000, 25_000, 34_000, 26_000, 15_000, 9_000, 5_000, 3_100, 1_800, 930, 550)

    fun bytes(area: Bbox, maxZoom: Int, density: Double = 2.0): Long {
        var total = 0.0
        for (z in 0..maxZoom.coerceIn(0, 15)) {
            val x0 = Pm.lonToX(area.minLon, z); val x1 = Pm.lonToX(area.maxLon, z)
            val y0 = Pm.latToY(area.maxLat, z); val y1 = Pm.latToY(area.minLat, z)
            val tiles = (x1 - x0 + 1).toDouble() * (y1 - y0 + 1).toDouble()
            // Low zooms are shared with the world map, so they're cheap; higher zooms scale with density (cities are dense).
            total += tiles * avg[z] * if (z >= 10) density else 1.0
        }
        return total.toLong()
    }
}

private class Bridge(val context: Context, val web: () -> WebView?, val onArea: (Bbox, Double) -> Unit, val onSearch: (String) -> Unit, val onLocate: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun requestLocation() = main.post { onLocate() }.let { }
    @JavascriptInterface fun downloadArea(w: Double, s: Double, e: Double, n: Double, zoom: Double) = main.post { onArea(Bbox(w, s, e, n), zoom) }.let { }
    @JavascriptInterface fun search(q: String) = main.post { onSearch(q) }.let { }
    @JavascriptInterface fun copy(text: String) = main.post {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Coordinates", text))
        Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
    }.let { }
}

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(nav: Nav, query: String) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    var area by remember { mutableStateOf<Bbox?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val lm = remember { ctx.getSystemService(LocationManager::class.java) }
    var listener by remember { mutableStateOf<LocationListener?>(null) }

    fun push(l: Location) { webRef?.evaluateJavascript("onLocation(${l.latitude},${l.longitude},${if (l.hasAccuracy()) l.accuracy else 0})", null) }

    fun startLocating() {
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) { Toast.makeText(ctx, "Turn on Location in your phone's settings to see where you are.", Toast.LENGTH_LONG).show(); return }
        // Show the last fix right away, then refine with a fresh GPS fix.
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { push(it) }
        Toast.makeText(ctx, "Finding your position… GPS works without signal but can take a minute outdoors.", Toast.LENGTH_SHORT).show()
        listener?.let { lm.removeUpdates(it) }
        val l = object : LocationListener {
            var fixes = 0
            override fun onLocationChanged(loc: Location) { push(loc); if (++fixes >= 3 || (loc.hasAccuracy() && loc.accuracy < 20)) lm.removeUpdates(this) }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        listener = l
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in providers) runCatching { lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()) }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) startLocating() else Toast.makeText(ctx, "Location permission is needed to show where you are.", Toast.LENGTH_LONG).show()
    }

    fun locate() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) startLocating() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val web = remember {
        arkWebView(ctx, argb(t.bg), onNavigate = { u -> if (!u.startsWith("https://ark.local/map")) { nav.read(u); true } else false }).also { w ->
            w.settings.builtInZoomControls = false
            w.addJavascriptInterface(Bridge(ctx, { webRef }, { b, _ -> area = b }, { q -> nav.go("search?q=$q") }, { locate() }), "ArkBridge")
            w.loadUrl("https://ark.local/map" + if (query.isNotEmpty()) "?$query" else "")
            webRef = w
        }
    }
    DisposableEffect(Unit) { onDispose { listener?.let { lm.removeUpdates(it) }; web.destroy() } }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("Map", nav::back) { TextButton(onClick = { nav.go("catalog/Maps") }) { Text("Map packs") } }
        AndroidView({ web }, Modifier.fillMaxSize())
    }

    area?.let { b -> AreaDialog(b, onDismiss = { area = null }) { title, zoom, size ->
        area = null
        val id = "map-area-" + System.currentTimeMillis()
        Ark.engine.enqueueCustom(Catalog.customMap(id, title, b, zoom, size))
        DownloadService.start(ctx)
        Toast.makeText(ctx, "Map area queued. You can keep using Ark while it downloads.", Toast.LENGTH_LONG).show()
    } }
}

@Composable
private fun AreaDialog(b: Bbox, onDismiss: () -> Unit, onConfirm: (String, Int, Long) -> Unit) {
    val t = LocalArk.current
    val options = listOf(12 to "Roads & towns", 14 to "Every street", 15 to "Streets & buildings")
    var zoom by remember { mutableIntStateOf(14) }
    val free = remember { Ark.storage.current().free() - Ark.storage.reserve }
    val sizes = options.associate { (z, _) -> z to MapEstimate.bytes(b, z) }
    val size = sizes[zoom] ?: 0
    val center = "%.2f°, %.2f°".format((b.minLat + b.maxLat) / 2, (b.minLon + b.maxLon) / 2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download this area") },
        text = {
            Column {
                Text("Saves the part of the map on screen so you can zoom in anywhere inside it offline.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                Spacer(Modifier.height(10.dp))
                options.forEach { (z, label) ->
                    Row(Modifier.fillMaxWidth().selectable(zoom == z) { zoom = z }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(zoom == z, { zoom = z }, colors = RadioButtonDefaults.colors(selectedColor = t.c(t.accent)))
                        Text(label, Modifier.weight(1f))
                        Text("≈ " + humanBytes(sizes[z] ?: 0), style = MaterialTheme.typography.labelMedium, color = t.c(t.muted))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("${humanBytes(free.coerceAtLeast(0))} free on this phone", style = MaterialTheme.typography.labelMedium,
                    color = if (size > free) t.c(t.danger) else t.c(t.muted))
                if (size > 20_000_000_000L) Text("That's a very large area. Zoom in on the map first, or choose less detail.", style = MaterialTheme.typography.bodySmall, color = t.c(t.danger))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm("Map area near $center", zoom, size) }, enabled = size <= free) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = t.c(t.surface),
    )
}
