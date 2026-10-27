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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doctorknepper.ark.Ark
import com.doctorknepper.ark.content.Hit
import com.doctorknepper.ark.core.TextKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class FieldTopic(val id: String, val title: String, val summary: String)

private val fieldTopics: List<FieldTopic> by lazy {
    val a = JSONArray(Ark.app.assets.open("field/index.json").use { String(it.readBytes()) })
    (0 until a.length()).map { a.getJSONObject(it).let { o -> FieldTopic(o.getString("id"), o.getString("title"), o.getString("summary")) } }
}

/** Searches every downloaded pack at once: field guide, Wikipedia, Kiwix packs and books. */
@Composable
fun SearchScreen(nav: Nav, initial: String) {
    val t = LocalArk.current
    var query by rememberSaveable { mutableStateOf(initial) }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var deep by remember { mutableStateOf<List<Hit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val nothingInstalled = Ark.store.packs.value.isEmpty()

    LaunchedEffect(query) {
        deep = null
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(180)
        busy = true
        hits = withContext(Dispatchers.IO) { searchAll(query) }
        busy = false
    }
    LaunchedEffect(Unit) { if (initial.isEmpty()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = t.c(t.accent)) }
            TextField(
                query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                placeholder = { Text("Search everything offline") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { hits.firstOrNull()?.let { open(nav, it) } }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = t.c(t.surface), unfocusedContainerColor = t.c(t.surface),
                    focusedIndicatorColor = t.c(t.bg), unfocusedIndicatorColor = t.c(t.bg), cursorColor = t.c(t.accent),
                ),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.bg))
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (query.isBlank()) {
                item {
                    if (nothingInstalled) {
                        Panel {
                            Text("Nothing downloaded yet", style = MaterialTheme.typography.titleSmall)
                            Text("The Field Guide is built in. Download Wikipedia, medical guides and books from the catalog to search them here.",
                                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { nav.go("catalog/") }) { Text("Open catalog") }
                        }
                    } else {
                        Text("Search titles across Wikipedia, every downloaded pack and your books. Use “Search inside pages” for words in the text.",
                            style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                }
            }
            val groups = hits.groupBy { it.sourceTitle }
            groups.forEach { (source, list) ->
                item(key = "h-$source") { Spacer(Modifier.height(10.dp)); Label(source) }
                items(list, key = { "r-" + it.source + it.url }) { h -> HitRow(h) { open(nav, h) } }
            }
            if (query.isNotBlank() && !busy) {
                item(key = "deep") {
                    Spacer(Modifier.height(14.dp))
                    if (deep == null) {
                        OutlinedButton(onClick = {
                            scope.launch { busy = true; deep = withContext(Dispatchers.IO) { Ark.zim.fullText(query, limit = 20) }; busy = false }
                        }) { Icon(Icons.Filled.ManageSearch, null); Spacer(Modifier.width(8.dp)); Text("Search inside pages") }
                        if (hits.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("No titles match “$query”.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                        }
                    } else {
                        Label("Inside pages")
                        if (deep!!.isEmpty()) Text("No pages contain those words. Full-text search works in Kiwix packs (medical, survival, repair and image packs).",
                            style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                }
                deep?.let { d -> items(d, key = { "d-" + it.source + it.url }) { h -> HitRow(h) { open(nav, h) } } }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun HitRow(h: Hit, onClick: () -> Unit) {
    val t = LocalArk.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = h.snippet?.replace(Regex("<[^>]+>"), "")?.takeIf { it.isNotBlank() }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun open(nav: Nav, h: Hit) {
    if (h.source == "books" && h.url.startsWith("ark-book:")) { nav.go(h.url.removePrefix("ark-book:")); return }
    nav.read(h.url)
}

/** Title search across all sources, cheapest and most important first. */
fun searchAll(query: String): List<Hit> {
    val q = TextKey.normalize(query)
    val out = ArrayList<Hit>()
    fieldTopics.filter { TextKey.normalize(it.title + " " + it.summary).contains(q) }
        .forEach { out.add(Hit("field", "Field Guide", it.title, "https://ark.local/field/${it.id}", it.summary)) }
    runCatching { out.addAll(Ark.wiki.search(query, 25)) }
    runCatching { out.addAll(Ark.zim.suggest(query, 6)) }
    runCatching { out.addAll(Ark.books.search(query, 15).map { it.copy(url = "ark-book:" + bookRoute(it.url)) }) }
    return out
}

/** Route for opening a book from its reader URL (PDFs open in the PDF viewer). */
fun bookRoute(url: String): String {
    val parts = url.removePrefix("https://ark.local/book/").trimEnd('/').split('/')
    val b = if (parts.size >= 2) Ark.books.find(parts[0], parts[1]) else null
    return if (b != null && b.format == "pdf") "pdf:${b.file.path}|${b.title}" else "read:$url"
}
