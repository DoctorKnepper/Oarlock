package com.doctorknepper.oarlock.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.core.Bbox
import com.doctorknepper.oarlock.core.humanBytes
import com.doctorknepper.oarlock.data.Catalog
import com.doctorknepper.oarlock.engine.DownloadService
import kotlin.math.cos

fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Gets one position fix (last known first, then a fresh GPS/network fix). Returns a function that stops listening. */
@SuppressLint("MissingPermission")
fun oneFix(ctx: Context, onFix: (Location) -> Unit, onFail: (String) -> Unit): () -> Unit {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val providers = lm.getProviders(true)
    if (providers.isEmpty()) { onFail("Location is turned off. Turn it on in your phone's settings."); return {} }
    providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        ?.takeIf { System.currentTimeMillis() - it.time < 6 * 3600_000L }?.let { onFix(it); return {} }
    val l = object : LocationListener {
        override fun onLocationChanged(loc: Location) { lm.removeUpdates(this); onFix(loc) }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }
    for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
        if (p in providers) runCatching { lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()) }
    }
    return { lm.removeUpdates(l) }
}

private data class AreaOption(val label: String, val detail: String, val km: Double, val zoom: Int)

/** Downloads a detailed map around the user's current position. */
@Composable
fun RegionDialog(onDismiss: () -> Unit, onQueued: () -> Unit) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    var fix by remember { mutableStateOf<Location?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stop by remember { mutableStateOf<() -> Unit>({}) }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) stop = oneFix(ctx, { fix = it }, { error = it })
        else error = "Location permission is needed to find your area. You can pick it on the map instead."
    }
    LaunchedEffect(Unit) {
        if (hasLocationPermission(ctx)) stop = oneFix(ctx, { fix = it }, { error = it })
        else if (!asked) { asked = true; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }
    DisposableEffect(Unit) { onDispose { stop() } }

    val options = listOf(
        AreaOption("Town", "about 25 km around you · streets and buildings", 25.0, 15),
        AreaOption("County", "about 60 km around you · every street", 60.0, 14),
        AreaOption("Region", "about 150 km around you · every street", 150.0, 14),
    )
    var choice by remember { mutableIntStateOf(1) }
    val free = remember { Ark.storage.current().free() - Ark.storage.reserve }
    fun area(o: AreaOption, l: Location): Bbox {
        val dLat = o.km / 111.0; val dLon = o.km / (111.0 * cos(Math.toRadians(l.latitude)).coerceAtLeast(0.1))
        return Bbox(l.longitude - dLon, l.latitude - dLat, l.longitude + dLon, l.latitude + dLat)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map of your area") },
        text = {
            Column {
                val l = fix
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = t.c(t.danger))
                    l == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = t.c(t.accent), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Finding your position… outdoors GPS can take a minute.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text("%.3f°, %.3f°".format(l.latitude, l.longitude), style = MaterialTheme.typography.labelMedium, color = t.c(t.muted))
                        Spacer(Modifier.height(8.dp))
                        options.forEachIndexed { i, o ->
                            val size = MapEstimate.bytes(area(o, l), o.zoom)
                            Row(Modifier.fillMaxWidth().selectable(choice == i) { choice = i }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(choice == i, { choice = i }, colors = RadioButtonDefaults.colors(selectedColor = t.c(t.accent)))
                                Column(Modifier.weight(1f)) {
                                    Text(o.label)
                                    Text(o.detail, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                                }
                                Text("≈ " + humanBytes(size), style = MaterialTheme.typography.labelMedium, color = if (size > free) t.c(t.danger) else t.c(t.muted))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${humanBytes(free.coerceAtLeast(0))} free. Sizes are estimates; cities take more room than the countryside.",
                            style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                }
            }
        },
        confirmButton = {
            val l = fix
            TextButton(enabled = l != null, onClick = {
                val o = options[choice]
                val b = area(o, l!!)
                val size = MapEstimate.bytes(b, o.zoom)
                Ark.settings.pausedByUser = false
                Ark.engine.enqueueCustom(Catalog.customMap("map-home-" + System.currentTimeMillis(), "Home area (${o.label.lowercase()}, %.2f°, %.2f°)".format(l.latitude, l.longitude), b, o.zoom, size))
                DownloadService.start(ctx)
                onQueued()
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = t.c(t.surface),
    )
}
