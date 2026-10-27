package com.doctorknepper.ark.web

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.WebResourceResponse
import com.doctorknepper.ark.Ark
import com.doctorknepper.ark.content.Book
import com.doctorknepper.ark.content.Epub
import com.doctorknepper.ark.content.WikiLibrary
import com.doctorknepper.ark.core.WikitextRenderer.Companion.esc
import com.doctorknepper.ark.core.WikitextRenderer.Companion.urlEnc
import com.doctorknepper.ark.engine.Http
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Everything the WebView shows is served from the virtual host https://ark.local/ — articles, Kiwix
 * pages, book chapters, map tiles and app assets — so pages work identically with no network at all.
 */
object ArkWeb {
    const val HOST = "ark.local"
    const val BASE = "https://ark.local"

    private fun ok(mime: String, bytes: ByteArray, cache: Boolean = false): WebResourceResponse {
        val (type, enc) = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml"))
            mime.substringBefore(';') to "utf-8" else mime to null
        val r = WebResourceResponse(type, enc, ByteArrayInputStream(bytes))
        r.responseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to if (cache) "max-age=86400" else "no-cache",
        )
        return r
    }

    private fun html(s: String) = ok("text/html", s.toByteArray())
    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))

    fun handle(url: Uri): WebResourceResponse? {
        if (url.host != HOST) return null
        val segs = url.pathSegments
        if (segs.isEmpty()) return notFound()
        return try {
            when (segs[0]) {
                "assets" -> asset(segs.drop(1).joinToString("/"))
                "theme.css" -> ok("text/css", Ark.theme.cssVars(Ark.settings.fontScale.value).toByteArray())
                "wiki" -> wikiPage(segs.getOrNull(1) ?: "wikipedia", segs.drop(2).joinToString("/"))
                "wimg" -> wikiImage(segs.drop(1).joinToString("/"))
                "zim" -> zimEntry(segs[1], url.encodedPath!!.substringAfter("/zim/${segs[1]}/"))
                "book" -> book(segs[1], segs[2], segs.drop(3).joinToString("/"))
                "tiles" -> tile(segs[1], segs[2].toInt(), segs[3].toLong(), segs[4].substringBefore('.').toLong())
                "map" -> mapPage(url)
                "field" -> fieldPage(segs.getOrNull(1) ?: "index")
                else -> notFound()
            }
        } catch (e: Exception) {
            html(shell("Problem", "<p class=\"notice\">This page couldn't be opened: ${esc(e.message ?: e.javaClass.simpleName)}</p>"))
        }
    }

    private fun asset(path: String): WebResourceResponse {
        val bytes = Ark.app.assets.open("web/$path").use { it.readBytes() }
        return ok(mimeOf(path), bytes, cache = true)
    }

    fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "css" -> "text/css"; "js" -> "application/javascript"; "json" -> "application/json"; "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "gif" -> "image/gif"
        "pbf" -> "application/x-protobuf"; "html", "htm", "xhtml" -> "text/html"; "woff2" -> "font/woff2"; "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** Standard page frame with Ark's article stylesheet and the current theme. */
    fun shell(title: String, body: String, subtitle: String = "", head: String = ""): String = """<!doctype html><html><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${esc(title)}</title>
        <link rel="stylesheet" href="/assets/article.css"><link rel="stylesheet" href="/theme.css">
        <script src="/assets/article.js"></script>$head</head><body><article>
        <h1>${esc(title)}</h1>${if (subtitle.isNotEmpty()) "<div class=\"subtitle\">$subtitle</div>" else ""}
        $body</article></body></html>""".trimIndent()

    // ------------------------------------------------------------- Wikipedia

    private fun wikiPage(packId: String, rawTitle: String): WebResourceResponse {
        val title = URLDecoder.decode(rawTitle, "UTF-8")
        return when (val r = Ark.wiki.article(packId, title)) {
            is WikiLibrary.Result.Article -> {
                val a = r.rendered
                val sub = StringBuilder(if (packId == "simplewiki") "Simple English Wikipedia" else "Wikipedia")
                r.redirectedFrom?.let { sub.append(" · Redirected from ").append(esc(it)) }
                val body = StringBuilder()
                if (a.lat != null && a.lon != null)
                    body.append("<a class=\"mapbtn\" href=\"$BASE/map?lat=${a.lat}&lon=${a.lon}&z=12&label=${urlEnc(a.title)}\">◉ Show on map</a>")
                if (a.toc.size >= 3) {
                    body.append("<details class=\"toc\"><summary>Contents</summary><ol>")
                    a.toc.forEach { body.append("<li class=\"l${it.level}\"><a href=\"#${it.id}\">${esc(it.title)}</a></li>") }
                    body.append("</ol></details>")
                }
                body.append(a.html)
                body.append("<hr><p class=\"license\">Text from Wikipedia, available under the Creative Commons Attribution-ShareAlike License.</p>")
                Ark.settings.lastArticle = "$packId|${a.title}"
                html(shell(a.title, body.toString(), sub.toString(), "<meta name=\"ark-title\" content=\"${esc(a.title)}\">"))
            }
            is WikiLibrary.Result.NotYet -> html(shell(title, "<p class=\"notice\">This article is listed, but Wikipedia's article text is still downloading. Check Downloads for progress — titles become readable as soon as it finishes.</p>"))
            is WikiLibrary.Result.Missing -> {
                val alts = Ark.wiki.search(title, 12) + Ark.zim.suggest(title, 3)
                val list = if (alts.isEmpty()) "" else "<h2>Similar pages</h2><ul>" + alts.joinToString("") { "<li><a href=\"${it.url}\">${esc(it.title)}</a> <small>· ${esc(it.sourceTitle)}</small></li>" } + "</ul>"
                html(shell(title, "<p class=\"notice\">There's no article with this title in your downloaded Wikipedia.</p>$list"))
            }
        }
    }

    private fun online(): Boolean {
        val cm = Ark.app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun wikiImage(rawName: String): WebResourceResponse {
        val name = URLDecoder.decode(rawName, "UTF-8")
        // 1. pictures inside downloaded Kiwix packs (WikiMed, Best of Wikipedia, topic packs)
        Ark.zim.wikiImage(name)?.let { (mime, bytes) -> return ok(mime, bytes, true) }
        // 2. pictures cached while online
        val cacheFile = File(Ark.imageCache, sha1(name))
        if (cacheFile.exists()) return ok("image/jpeg", cacheFile.readBytes(), true)
        // 3. fetch from Wikimedia Commons when online
        if (Ark.settings.onlineImages.value && online()) {
            val lower = name.lowercase()
            val width = if (lower.endsWith(".svg")) "?width=480" else "?width=640"
            val bytes = Http.get("https://commons.wikimedia.org/wiki/Special:FilePath/" + urlEnc(name) + width)
            if (Ark.settings.cacheImages.value) runCatching { cacheFile.writeBytes(bytes) }
            return ok(if (lower.endsWith(".png") || lower.endsWith(".svg")) "image/png" else "image/jpeg", bytes, true)
        }
        return notFound()
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------- Kiwix packs

    private const val ZIM_INJECT = "<link rel=\"stylesheet\" href=\"/theme.css\"><link rel=\"stylesheet\" href=\"/assets/zim.css\">"

    private fun zimEntry(packId: String, path: String): WebResourceResponse {
        val p = if (path.isEmpty()) Ark.zim.mainPath(packId) ?: return notFound() else path
        val (mime, bytes) = Ark.zim.entry(packId, p) ?: return notFound()
        if (mime.startsWith("text/html")) {
            var s = String(bytes, Charsets.UTF_8)
            val i = s.indexOf("</head>", ignoreCase = true)
            s = if (i >= 0) s.substring(0, i) + ZIM_INJECT + s.substring(i) else ZIM_INJECT + s
            return ok("text/html", s.toByteArray())
        }
        return ok(mime, bytes, true)
    }

    // ------------------------------------------------------------- books

    private fun book(packId: String, bookId: String, path: String): WebResourceResponse {
        val b = Ark.books.find(packId, bookId) ?: return notFound()
        return when (b.format) {
            "epub" -> epub(b, path)
            "txt" -> html(shell(b.title, textBook(b.file.readText()), esc(b.author)))
            else -> notFound()
        }
    }

    private fun epub(b: Book, path: String): WebResourceResponse {
        val e = Ark.books.epub(b) ?: return notFound()
        if (path.isEmpty()) return html(bookContents(b, e))
        val p = URLDecoder.decode(path, "UTF-8")
        val bytes = e.bytes(p) ?: return notFound()
        val mime = e.mime(p)
        if (mime.contains("html") || mime.contains("xml") && p.endsWith("html")) {
            var s = String(bytes, Charsets.UTF_8)
            val idx = e.spine.indexOf(p)
            val prev = e.spine.getOrNull(idx - 1); val next = e.spine.getOrNull(idx + 1)
            val nav = "<nav class=\"arkbooknav\">" +
                (if (prev != null) "<a href=\"${b.url}${prev}\">‹ Previous</a>" else "<span></span>") +
                "<a href=\"${b.url}\">Contents</a>" +
                (if (next != null) "<a href=\"${b.url}${next}\">Next ›</a>" else "<span></span>") + "</nav>"
            s = s.replace(Regex("<\\?xml[^>]*\\?>"), "")
            val head = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"/theme.css\"><link rel=\"stylesheet\" href=\"/assets/book.css\">"
            s = s.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$head</head>")
            Regex("<body([^>]*)>", RegexOption.IGNORE_CASE).find(s)?.let { m -> s = s.replaceRange(m.range, "<body${m.groupValues[1]}><div class=\"arkbook\">") }
            s = s.replaceFirst(Regex("</body>", RegexOption.IGNORE_CASE), "</div>$nav</body>")
            return ok("text/html", s.toByteArray())
        }
        return ok(mime, bytes, true)
    }

    private fun bookContents(b: Book, e: Epub): String {
        val first = e.spine.firstOrNull()
        val sb = StringBuilder()
        if (first != null) sb.append("<p><a class=\"mapbtn\" href=\"${b.url}$first\">Start reading ›</a></p>")
        sb.append("<h2>Contents</h2><ol class=\"bookToc\">")
        e.toc.forEach { sb.append("<li><a href=\"${b.url}${it.href}\">${esc(it.title)}</a></li>") }
        sb.append("</ol><p class=\"license\">Public-domain text.</p>")
        return shell(b.title, sb.toString(), esc(listOf(b.author, b.genre).filter { it.isNotBlank() }.joinToString(" · ")))
    }

    private fun textBook(t: String): String {
        var text = t
        val start = text.indexOf("*** START OF"); if (start >= 0) text = text.substring(text.indexOf('\n', start) + 1)
        val end = text.indexOf("*** END OF"); if (end > 0) text = text.substring(0, end)
        return text.split(Regex("\\n\\s*\\n")).joinToString("") { "<p>" + esc(it.trim()).replace("\n", " ") + "</p>" }
    }

    // ------------------------------------------------------------- maps

    private fun tile(packId: String, z: Int, x: Long, y: Long): WebResourceResponse {
        val t = Ark.maps.tile(packId, z, x, y) ?: return WebResourceResponse("application/x-protobuf", null, 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
        return ok("application/x-protobuf", t, true)
    }

    private fun mapPage(url: Uri): WebResourceResponse {
        val tpl = Ark.app.assets.open("web/map.html").use { String(it.readBytes()) }
        val t = Ark.theme
        val cfg = org.json.JSONObject()
            .put("sources", org.json.JSONArray(Ark.maps.sourcesJson()))
            .put("dark", t.dark).put("bg", t.css(t.bg)).put("accent", t.css(t.accent)).put("text", t.css(t.text)).put("muted", t.css(t.muted))
            .put("surface", t.css(t.surface)).put("line", t.css(t.line))
            .put("lat", url.getQueryParameter("lat")?.toDoubleOrNull() ?: org.json.JSONObject.NULL)
            .put("lon", url.getQueryParameter("lon")?.toDoubleOrNull() ?: org.json.JSONObject.NULL)
            .put("z", url.getQueryParameter("z")?.toDoubleOrNull() ?: org.json.JSONObject.NULL)
            .put("label", url.getQueryParameter("label") ?: "")
        return html(tpl.replace("/*ARK_CONFIG*/null", cfg.toString()))
    }

    // ------------------------------------------------------------- field guide

    private fun fieldPage(id: String): WebResourceResponse {
        val index = org.json.JSONArray(Ark.app.assets.open("field/index.json").use { String(it.readBytes()) })
        val entry = (0 until index.length()).map { index.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: return notFound()
        val body = Ark.app.assets.open("field/$id.html").use { String(it.readBytes()) }
        return html(shell(entry.getString("title"), body, "Ark Field Guide"))
    }
}
