package com.doctorknepper.ark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.doctorknepper.ark.Ark

/** Reads any Ark page (Wikipedia, Kiwix packs, books, field guide) in a themed offline WebView. */
@Composable
fun ReaderScreen(nav: Nav, startUrl: String) {
    val t = LocalArk.current
    val ctx = LocalContext.current
    // Saved per back-stack entry so returning to this page reopens where you were.
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(startUrl) }
    var loading by remember { mutableStateOf(true) }
    var finding by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val saved by Ark.settings.saved.collectAsState()
    val themeId by Ark.settings.theme.collectAsState()
    val fontScale by Ark.settings.fontScale.collectAsState()
    var canGoBack by remember { mutableStateOf(false) }

    val web = remember(startUrl) {
        arkWebView(
            ctx, argb(t.bg),
            onTitle = { title = it },
            onPageUrl = { u ->
                url = u; loading = false
                if (title.isNotBlank()) Ark.settings.setLastPage(title, u)
            },
            onNavigate = { u ->
                if (u.startsWith("https://ark.local/map")) { nav.read(u); true } else { loading = true; false }
            },
        ).apply {
            setFindListener { active, total, done -> if (done) matches = if (total == 0) "0" else "${active + 1}/$total" }
            loadUrl(url)
        }
    }
    // Keep the WebView's back history in sync for the system back gesture.
    LaunchedEffect(url) { canGoBack = web.canGoBack() }
    // Re-theme open pages when colors or text size change.
    var firstTheme by remember { mutableIntStateOf(0) }
    LaunchedEffect(themeId, fontScale) { if (firstTheme++ > 0) { web.setBackgroundColor(argb(Ark.theme.bg)); web.reload() } }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }

    BackHandler(enabled = finding || canGoBack) {
        when {
            finding -> { finding = false; web.clearMatches() }
            web.canGoBack() -> { web.goBack(); canGoBack = web.canGoBack() }
        }
    }

    Column(Modifier.fillMaxSize().background(t.c(t.bg))) {
        if (finding) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finding = false; web.clearMatches() }) { Icon(Icons.Filled.Close, "Close", tint = t.c(t.accent)) }
                TextField(
                    findText, { findText = it; if (it.isNotEmpty()) web.findAllAsync(it) else { web.clearMatches(); matches = "" } },
                    Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in page") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { web.findNext(true) }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = t.c(t.surface), unfocusedContainerColor = t.c(t.surface), focusedIndicatorColor = t.c(t.accent)),
                )
                Text(matches, style = MaterialTheme.typography.labelMedium, color = t.c(t.muted), modifier = Modifier.padding(horizontal = 6.dp))
                IconButton(onClick = { web.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous", tint = t.c(t.accent)) }
                IconButton(onClick = { web.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next", tint = t.c(t.accent)) }
            }
        } else {
            ArkTopBar(title.ifEmpty { "Loading…" }, nav::back) {
                val isSaved = saved.any { it.second == url }
                IconButton(onClick = { Ark.settings.toggleSaved(title.ifEmpty { url }, url) }) {
                    Icon(if (isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (isSaved) "Remove from saved" else "Save page")
                }
                IconButton(onClick = { nav.go("search") }) { Icon(Icons.Filled.Search, "Search") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }, containerColor = t.c(t.surface)) {
                        DropdownMenuItem({ Text("Find in page") }, { menu = false; finding = true }, leadingIcon = { Icon(Icons.Filled.FindInPage, null) })
                        DropdownMenuItem({ Text("Larger text") }, { menu = false; Ark.settings.setFontScale((fontScale + 0.1f).coerceAtMost(1.8f)) })
                        DropdownMenuItem({ Text("Smaller text") }, { menu = false; Ark.settings.setFontScale((fontScale - 0.1f).coerceAtLeast(0.7f)) })
                        DropdownMenuItem({ Text("Home") }, { menu = false; nav.go("home") }, leadingIcon = { Icon(Icons.Filled.Home, null) })
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.c(t.accent), trackColor = t.c(t.bg))
        AndroidView({ web }, Modifier.fillMaxSize())
    }
}
