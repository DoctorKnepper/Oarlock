package com.doctorknepper.ark.content

import com.doctorknepper.ark.core.TextKey
import com.doctorknepper.ark.data.PackKind
import com.doctorknepper.ark.data.PackStore
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

data class Book(val packId: String, val packTitle: String, val id: String, val title: String, val author: String, val genre: String, val file: File) {
    val format get() = file.extension.lowercase()
    val url get() = "https://ark.local/book/$packId/$id/"
}

data class Chapter(val title: String, val href: String)

class Epub(val file: File) {
    val zip = ZipFile(file)
    val opfPath: String
    val base: String
    val title: String
    val spine: List<String>
    val toc: List<Chapter>
    private val mediaTypes = HashMap<String, String>()

    init {
        val container = text("META-INF/container.xml") ?: ""
        opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: "content.opf"
        base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = text(opfPath) ?: ""
        title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)?.trim() ?: file.nameWithoutExtension
        val manifest = HashMap<String, String>()
        var navHref: String? = null; var ncxHref: String? = null
        Regex("<item\\s[^>]*>").findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href")?.let { resolve(base, it) } ?: return@forEach
            manifest[id] = href
            attr(tag, "media-type")?.let { mediaTypes[href] = it }
            if (attr(tag, "properties")?.contains("nav") == true) navHref = href
            if (attr(tag, "media-type") == "application/x-dtbncx+xml") ncxHref = href
        }
        spine = Regex("<itemref\\s[^>]*idref=\"([^\"]+)\"[^>]*>").findAll(opf).mapNotNull { manifest[it.groupValues[1]] }.toList()
        toc = parseNav(navHref) ?: parseNcx(ncxHref) ?: spine.mapIndexed { i, h -> Chapter("Section ${i + 1}", h) }
    }

    private fun attr(tag: String, name: String) = Regex("\\s$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    fun text(path: String): String? = bytes(path)?.toString(Charsets.UTF_8)

    fun bytes(path: String): ByteArray? {
        val e = zip.getEntry(path) ?: zip.getEntry(URLDecoder.decode(path, "UTF-8")) ?: return null
        return zip.getInputStream(e).use { it.readBytes() }
    }

    fun mime(path: String): String = mediaTypes[path] ?: when (path.substringAfterLast('.').lowercase()) {
        "xhtml", "html", "htm" -> "text/html"; "css" -> "text/css"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
        "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; else -> "application/octet-stream"
    }

    private fun parseNav(href: String?): List<Chapter>? {
        val html = href?.let { text(it) } ?: return null
        val nav = Regex("<nav[^>]*epub:type=\"toc\"[^>]*>(.*?)</nav>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: html
        val dir = href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val out = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(nav).map {
            Chapter(clean(it.groupValues[2]), resolve(dir, it.groupValues[1]))
        }.filter { it.title.isNotBlank() }.toList()
        return out.ifEmpty { null }
    }

    private fun parseNcx(href: String?): List<Chapter>? {
        val xml = href?.let { text(it) } ?: return null
        val dir = href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val out = Regex("<navPoint[^>]*>\\s*<navLabel>\\s*<text>(.*?)</text>\\s*</navLabel>\\s*<content\\s+src=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { Chapter(clean(it.groupValues[1]), resolve(dir, it.groupValues[2])) }.toList()
        return out.ifEmpty { null }
    }

    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").replace("&amp;", "&").trim()

    companion object {
        fun resolve(dir: String, href: String): String {
            if (href.startsWith("/")) return href.trimStart('/')
            val parts = (dir + href).split('/').toMutableList()
            val out = ArrayList<String>()
            for (p in parts) when (p) { ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); ".", "" -> {}; else -> out.add(p) }
            return out.joinToString("/")
        }
    }
}

/** All downloaded books (curated collections and single books), with EPUB reading support. */
class BookLibrary(private val store: PackStore) {
    private val epubs = object : LinkedHashMap<String, Epub>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Epub>?): Boolean {
            if (size > 6) { runCatching { eldest?.value?.zip?.close() }; return true }
            return false
        }
    }

    fun books(): List<Book> = store.packs.value.values.filter { it.kind == PackKind.BOOKS }.flatMap { p ->
        val arr = p.info.optJSONArray("items") ?: return@flatMap emptyList<Book>()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val f = File(p.dir, o.optString("file"))
            if (!f.exists()) null
            else Book(p.id, p.title, f.nameWithoutExtension, o.optString("t"), o.optString("a"), o.optString("g"), f)
        }
    }

    fun find(packId: String, bookId: String) = books().firstOrNull { it.packId == packId && it.id == bookId }

    fun search(query: String, limit: Int = 20): List<Hit> {
        val q = TextKey.normalize(query)
        if (q.isEmpty()) return emptyList()
        return books().filter { TextKey.normalize(it.title + " " + it.author).contains(q) }.take(limit)
            .map { Hit("books", "Books", it.title, it.url, it.author) }
    }

    @Synchronized
    fun epub(book: Book): Epub? = try { epubs.getOrPut(book.file.path) { Epub(book.file) } } catch (e: Exception) { null }
}
