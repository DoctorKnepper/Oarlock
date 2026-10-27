package com.doctorknepper.oarlock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In Case of Emergency card. Stored only in this app's private storage on the phone: it is never uploaded,
 * and the app opts out of Android backup, so it isn't copied to the cloud either.
 */
object Ice {
    val fields = listOf(
        "name" to "Name", "dob" to "Date of birth", "blood" to "Blood type", "allergies" to "Allergies",
        "meds" to "Medications", "conditions" to "Medical conditions", "doctor" to "Doctor & phone", "notes" to "Other notes (organ donor, implants, language…)",
    )
    val contacts = (1..3).flatMap { listOf("c${it}name" to "Contact $it name & relationship", "c${it}phone" to "Contact $it phone") }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ice", Context.MODE_PRIVATE)
    fun load(ctx: Context): Map<String, String> = prefs(ctx).all.mapValues { it.value.toString() }
    fun save(ctx: Context, data: Map<String, String>) {
        val e = prefs(ctx).edit().clear()
        data.filterValues { it.isNotBlank() }.forEach { (k, v) -> e.putString(k, v.trim()) }
        e.apply()
    }
}

@Composable
fun IceScreen(nav: Nav) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val data = remember { mutableStateMapOf<String, String>().apply { putAll(Ice.load(ctx)) } }
    var editing by remember { mutableStateOf(data.isEmpty()) }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("ICE · In Case of Emergency", nav::back) {
            if (editing) TextButton(onClick = { Ice.save(ctx, data); editing = false }) { Text("Save") }
            else TextButton(onClick = { editing = true }) { Text("Edit") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Stored only on this phone. Never uploaded or backed up. Anyone who opens Oarlock can see it, which is the point in an emergency.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            Spacer(Modifier.height(12.dp))
            if (editing) {
                val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.c(t.accent), unfocusedBorderColor = t.c(t.line), focusedLabelColor = t.c(t.accent), cursorColor = t.c(t.accent))
                (Ice.fields + Ice.contacts).forEach { (k, label) ->
                    OutlinedTextField(
                        data[k] ?: "", { data[k] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) },
                        singleLine = !(k == "meds" || k == "conditions" || k == "allergies" || k == "notes"),
                        keyboardOptions = if (k.endsWith("phone")) KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions.Default,
                        colors = colors,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Ice.save(ctx, data); editing = false }, colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) { Text("Save") }
                    OutlinedButton(onClick = { data.clear(); data.putAll(Ice.load(ctx)); if (data.isNotEmpty()) editing = false }) { Text("Cancel") }
                }
            } else {
                Panel {
                    data["name"]?.let { Text(it, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = t.c(t.accent)) }
                    data["dob"]?.let { Text("Born $it", style = MaterialTheme.typography.bodyLarge) }
                    data["blood"]?.let { Spacer(Modifier.height(8.dp)); Big("Blood type", it) }
                    for ((k, label) in Ice.fields.drop(3)) data[k]?.let { Spacer(Modifier.height(10.dp)); Big(label.substringBefore(" ("), it, danger = k == "allergies") }
                }
                Spacer(Modifier.height(12.dp))
                Label("Emergency contacts")
                for (i in 1..3) {
                    val name = data["c${i}name"]; val phone = data["c${i}phone"]
                    if (name == null && phone == null) continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name ?: "Contact $i", style = MaterialTheme.typography.titleMedium)
                            phone?.let { Text(it, fontSize = 22.sp, color = t.c(t.text)) }
                        }
                        if (phone != null) Button(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { c -> c.isDigit() || c == '+' }))) } },
                            colors = ButtonDefaults.buttonColors(containerColor = t.c(t.accent), contentColor = t.c(t.onAccent))) {
                            Icon(Icons.Filled.Call, null); Spacer(Modifier.width(6.dp)); Text("Call")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Tip: also put your emergency info in your phone's own Emergency information settings, which can be seen from the lock screen.",
                    style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Big(label: String, value: String, danger: Boolean = false) {
    val t = LocalArk.current
    Label(label, color = if (danger) t.c(t.danger) else t.c(t.muted))
    Text(value, fontSize = 20.sp, color = t.c(t.text))
}
