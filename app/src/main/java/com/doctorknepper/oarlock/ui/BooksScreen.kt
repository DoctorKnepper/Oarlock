package com.doctorknepper.oarlock.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doctorknepper.oarlock.Ark
import com.doctorknepper.oarlock.content.Book
import com.doctorknepper.oarlock.content.PgBook
import com.doctorknepper.oarlock.data.BookItem
import com.doctorknepper.oarlock.data.PackDef
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.engine.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BooksScreen(nav: Nav) {
    val t = LocalArk.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val packs by Ark.store.packs.collectAsState()
    val mine = remember(packs) { Ark.books.books() }
    LaunchedEffect(Unit) { if (mine.isEmpty() && tab == 0) tab = 1 }
    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        ArkTopBar("Books", nav::back)
        TabRow(selectedTabIndex = tab, containerColor = t.c(t.bg), contentColor = t.c(t.accent)) {
            listOf("My books", "Collections", "All of Gutenberg").forEachIndexed { i, s -> Tab(tab == i, { tab = i }, text = { Text(s, maxLines = 1) }) }
        }
        when (tab) {
            0 -> MyBooks(nav, mine) { tab = 1 }
            1 -> Collections(nav)
            else -> GutenbergBrowser(nav)
        }
    }
}

@Composable
private fun MyBooks(nav: Nav, books: List<Book>, browse: () -> Unit) {
    val t = LocalArk.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (books.isEmpty()) {
            item {
                Panel {
                    Text("No books yet", style = MaterialTheme.typography.titleSmall)
                    Text("Download a collection, such as Sacred & Spiritual Texts, or pick single books from Project Gutenberg's 60,000+ public-domain titles.",
                        style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = browse) { Text("Browse collections") }
                }
            }
        }
        books.groupBy { it.genre.ifBlank { "Other" } }.toSortedMap().forEach { (genre, list) ->
            item(key = "g-$genre") { Spacer(Modifier.height(12.dp)); Label("$genre · ${list.size}") }
            items(list.sortedBy { it.title }, key = { "b-${it.packId}-${it.id}" }) { b ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(bookRoute(b.url)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(b.author, b.format.uppercase()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = t.c(t.muted))
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun Collections(nav: Nav) {
    val t = LocalArk.current
    val packs by Ark.store.packs.collectAsState()
    val engine by Ark.engine.state.collectAsState()
    val storage = remember(packs, engine.queue.size) { storageInfo() }
    val pending = engine.queue.sumOf { j -> (Ark.engine.defOf(j)?.size ?: 0) - (Ark.store[j.packId]?.bytes ?: 0) }.coerceAtLeast(0)
    val curated = Ark.catalog.packs.filter { it.kind == PackKind.BOOKS }
    val shelves = Ark.catalog.packs.filter { it.category == "Books" && it.kind != PackKind.BOOKS && it.kind != PackKind.GUTENBERG }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StorageBar(storage, pending) }
        item {
            Text("Hand-picked public-domain books, grouped by genre. Each collection is small; tap one to see every title.",
                style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
        }
        items(curated, key = { it.id }) { PackCard(it, nav, storage.free - pending) }
        if (shelves.isNotEmpty()) {
            item { Spacer(Modifier.height(6.dp)); Label("Whole libraries (large)") }
            items(shelves, key = { it.id }) { PackCard(it, nav, storage.free - pending) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/** Definition used to download one book into "My Books". */
fun singleBookDef(item: BookItem): PackDef {
    val o = JSONObject().put("id", "book-" + item.id).put("kind", "single_book").put("title", item.title).put("size", 1_500_000L)
        .put("category", "Books")
        .put("items", JSONArray().put(JSONObject().put("src", item.src).put("t", item.title).put("a", item.author).put("g", item.genre)))
    return PackDef("book-" + item.id, PackKind.SINGLE_BOOK, item.title, item.author, "Books", 1_500_000L, o)
}

@Composable
private fun GutenbergBrowser(nav: Nav) {
    val t = LocalArk.current
    val packs by Ark.store.packs.collectAsState()
    val available = remember(packs) { Ark.gutenberg.available() }
    if (!available) {
        val storage = remember(packs) { storageInfo() }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Project Gutenberg has more than 60,000 public-domain books. Download its catalog once (about 30 MB) to browse every English title by genre and pick any book.",
                    style = MaterialTheme.typography.bodySmall, color = t.c(t.muted))
            }
            Ark.catalog["gutenberg-catalog"]?.let { def -> item { PackCard(def, nav, storage.free) } }
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<PgBook>>(emptyList()) }
    val genres = remember(available) { Ark.gutenberg.genres() }
    LaunchedEffect(query, genre) {
        delay(200)
        results = withContext(Dispatchers.IO) {
            when {
                query.isNotBlank() -> Ark.gutenberg.search(query)
                genre != null -> Ark.gutenberg.byGenre(genre!!)
                else -> emptyList()
            }
        }
    }
    Column {
        TextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true, placeholder = { Text("Search title, author or subject") },
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = t.c(t.surface), unfocusedContainerColor = t.c(t.surface),
                focusedIndicatorColor = t.c(t.bg), unfocusedIndicatorColor = t.c(t.bg)),
        )
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (query.isBlank() && genre == null) {
                item { Label("Genres") }
                items(genres, key = { "genre-" + it.first }) { (g, n) ->
                    Row(Modifier.fillMaxWidth().clickable { genre = g }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("%,d".format(n), style = MaterialTheme.typography.labelMedium, color = t.c(t.muted))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.ChevronRight, null, tint = t.c(t.muted))
                    }
                }
            } else {
                if (genre != null && query.isBlank()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(genre!!, Modifier.weight(1f))
                        OutlinedButton(onClick = { genre = null }) { Text("All genres") }
                    }
                }
                items(results, key = { "pg-" + it.id }) { b -> PgRow(b) }
                if (results.isEmpty()) item { Text("No books found.", style = MaterialTheme.typography.bodySmall, color = t.c(t.muted)) }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun PgRow(b: PgBook) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    val packs by Ark.store.packs.collectAsState()
    val engine by Ark.engine.state.collectAsState()
    val item = b.item
    val have = remember(packs) { Ark.books.books().any { it.id == item.id } }
    val queued = engine.queue.any { it.packId == "book-" + item.id }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.author.ifBlank { "Unknown author" }, style = MaterialTheme.typography.bodySmall, color = t.c(t.muted), maxLines = 1)
        }
        when {
            have -> Icon(Icons.Filled.CheckCircle, "Downloaded", tint = t.c(t.accent), modifier = Modifier.padding(12.dp))
            queued -> Icon(Icons.Filled.Schedule, "Queued", tint = t.c(t.muted), modifier = Modifier.padding(12.dp))
            else -> IconButton(onClick = { Ark.engine.enqueueCustom(singleBookDef(item)); DownloadService.start(ctx) }) {
                Icon(Icons.Filled.Download, "Download", tint = t.c(t.accent))
            }
        }
    }
}
