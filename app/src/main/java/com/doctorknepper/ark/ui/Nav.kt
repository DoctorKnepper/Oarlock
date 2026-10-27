package com.doctorknepper.ark.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * Minimal back-stack navigation. Routes are plain strings:
 * "home", "search", "search?q=…", "catalog/<category>", "downloads", "defcon", "library", "books",
 * "map", "map?<query>", "settings", "field", "read:<url>", "pdf:<file path>|<title>".
 */
class Nav(start: String = "home") {
    val stack = mutableStateListOf(start)
    val current: String get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun go(route: String) {
        if (route == current) return
        if (route == "home") { stack.clear(); stack.add("home"); return }
        stack.add(route)
    }

    /** Opens a page in the reader. Map links open the map screen instead. */
    fun read(url: String) {
        if (url.startsWith("https://ark.local/map")) { go("map" + url.substringAfter("/map", "").let { if (it.startsWith("?")) it else "" }); return }
        go("read:$url")
    }

    fun replace(route: String) { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); stack.add(route) }

    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
}
