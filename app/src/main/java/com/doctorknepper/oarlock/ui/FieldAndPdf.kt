package com.doctorknepper.oarlock.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.doctorknepper.oarlock.Ark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Built-in emergency guide: always available, no download needed. */
@Composable
fun FieldGuideScreen(nav: Nav) {
    val t = LocalArk.current
    val lang by Ark.settings.fieldLang.collectAsState()
    val dir = if (lang == "es") "es/" else ""
    val topics = remember(lang) {
        val a = JSONArray(Ark.app.assets.open("field/${dir}index.json").use { String(it.readBytes()) })
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar(if (lang == "es") "Guía de campo" else "Field Guide", nav::back) {
            TextButton(onClick = { Ark.settings.setFieldLang(if (lang == "es") "en" else "es") }) { Text(if (lang == "es") "English" else "Español") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(if (lang == "es") "Incluida en la app y siempre disponible. En una emergencia que pone en peligro la vida, llame primero al 911 (o al número de emergencias local) si puede."
                    else "Built in and always available. In a life-threatening emergency, call 911 (or your local emergency number) first if you can.",
                    style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                Spacer(Modifier.height(4.dp))
                Panel(onClick = { nav.go("ice") }) {
                    Text(if (lang == "es") "Tarjeta ICE: información médica y contactos de emergencia" else "ICE card: your medical info and emergency contacts",
                        style = MaterialTheme.typography.titleSmall, color = t.c(t.accent))
                }
                Spacer(Modifier.height(4.dp))
            }
            itemsIndexed(topics) { _, o ->
                Panel(onClick = { nav.read("https://ark.local/field/" + dir + o.getString("id")) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.getString("title"), style = MaterialTheme.typography.titleSmall)
                            Text(o.getString("summary"), style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = t.c(t.muted))
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(if (lang == "es") "La guía en español cubre los temas más urgentes. Para mucho más (en inglés), descargue WikEM, WikiMed y los manuales del Ejército."
                    else "For much more, download WikEM, WikiMed, the Medical Library and the Army survival manuals from the catalog.",
                    style = MaterialTheme.typography.bodySmall, color = t.c(t.muted), modifier = Modifier.clickable { nav.go("catalog/Medical") })
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** Pages of a PDF rendered on demand (scanned books from the Internet Archive). */
@Composable
fun PdfScreen(nav: Nav, path: String, title: String) {
    val t = LocalArk.current
    val file = remember { File(path) }
    val doc = remember {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { runCatching { doc?.close() } } }
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar(title, nav::back) {
            if (doc != null) Text("${state.firstVisibleItemIndex + 1} / ${doc.pageCount}", style = MaterialTheme.typography.labelMedium,
                color = t.c(t.muted), modifier = Modifier.padding(end = 12.dp))
        }
        if (doc == null) {
            Text("This PDF couldn't be opened. It may be damaged; delete the book and download it again.", Modifier.padding(16.dp), color = t.c(t.muted))
            return
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtMost(1600)
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(doc.pageCount) { i -> PdfPage(doc, i, widthPx) }
            }
        }
    }
}

private val pdfLock = Any()

@Composable
private fun PdfPage(doc: PdfRenderer, index: Int, widthPx: Int) {
    val ratio = remember(index) { synchronized(pdfLock) { doc.openPage(index).use { it.height.toFloat() / it.width } } }
    var bmp by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) {
        bmp = withContext(Dispatchers.IO) {
            synchronized(pdfLock) {
                doc.openPage(index).use { page ->
                    val b = Bitmap.createBitmap(widthPx, (widthPx * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(AColor.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
    }
    DisposableEffect(index) { onDispose { bmp = null } }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(LocalArk.current.c(LocalArk.current.surface))) {
        bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth) }
    }
}
