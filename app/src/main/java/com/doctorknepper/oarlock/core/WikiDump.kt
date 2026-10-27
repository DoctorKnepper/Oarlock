package com.doctorknepper.oarlock.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.channels.Channels

data class WikiPage(val id: Long, val title: String, val redirect: String?, val text: String)

data class TitleHit(val title: String, val offset: Long, val pageId: Long)

/** Counts bytes read so long-running jobs can report progress against a compressed file size. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L; private set
    override fun read(): Int { val b = super.read(); if (b >= 0) count++; return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) count += n; return n }
    override fun skip(n: Long): Long { val s = super.skip(n); count += s; return s }
}

class Cancelled : Exception("cancelled")

object WikiNamespaces {
    /** Namespaces present in pages-articles dumps that are not readable articles. */
    private val skipped = setOf(
        "wikipedia", "wp", "project", "file", "image", "mediawiki", "template", "help", "category", "portal",
        "draft", "timedtext", "module", "book", "education program", "gadget", "gadget definition", "topic",
        "user", "talk", "special", "media", "event", "transwiki", "wikiproject"
    )

    fun isArticle(title: String): Boolean {
        val c = title.indexOf(':')
        if (c <= 0) return true
        val ns = title.substring(0, c).trim().lowercase()
        if (ns.endsWith(" talk")) return false
        return ns !in skipped
    }
}

/** Title search over the dump, stored as a [SortedIndex] keyed by normalized title. */
class TitleIndex(file: File) : Closeable {
    private val index = SortedIndex.open(file)

    private fun decode(r: SortedIndex.Record): TitleHit {
        val s = ByteSource(r.value)
        return TitleHit(s.string(), s.varint(), s.varint())
    }

    fun search(query: String, limit: Int = 40): List<TitleHit> {
        val key = TextKey.keyBytes(query)
        if (key.isEmpty()) return emptyList()
        val hits = index.prefix(key, limit * 3).map { decode(it) }
        // Exact key first, then shorter titles (more general articles) first.
        val norm = TextKey.normalize(query)
        return hits.sortedWith(compareBy<TitleHit>({ TextKey.normalize(it.title) != norm }, { it.title.length }))
            .distinctBy { it.title }.take(limit)
    }

    fun resolve(rawTitle: String): TitleHit? {
        val canonical = TextKey.canonicalTitle(rawTitle)
        if (canonical.isEmpty()) return null
        val hits = index.exact(TextKey.keyBytes(canonical), 64).map { decode(it) }
        return hits.firstOrNull { it.title == canonical }
            ?: hits.firstOrNull { it.title.equals(canonical, ignoreCase = true) }
            ?: hits.firstOrNull()
    }

    fun random(rng: java.util.Random = java.util.Random()): TitleHit? {
        if (index.blockCount == 0) return null
        repeat(20) {
            val b = rng.nextInt(index.blockCount)
            val block = index.block(b)
            if (block.isNotEmpty()) {
                val hit = decode(block[rng.nextInt(block.size)])
                if (!hit.title.contains("(disambiguation)")) return hit
            }
        }
        return null
    }

    override fun close() = index.close()

    companion object {
        fun encode(title: String, offset: Long, pageId: Long): ByteArray {
            val s = ByteSink(title.length + 16); s.string(title); s.varint(offset); s.varint(pageId)
            return s.toByteArray()
        }

        /**
         * Builds the title index from Wikimedia's "multistream-index.txt.bz2"
         * (lines of "streamOffset:pageId:Title").
         */
        fun build(indexBz2: File, target: File, workDir: File, onProgress: (Double, Long) -> Unit, isCancelled: () -> Boolean): Long {
            val total = indexBz2.length().toDouble()
            val counting = CountingInputStream(BufferedInputStream(FileInputStream(indexBz2), 1 shl 16))
            val builder = SortedIndex.ExternalBuilder(workDir)
            var kept = 0L
            BufferedReader(InputStreamReader(BZip2CompressorInputStream(counting, true), Charsets.UTF_8), 1 shl 16).use { reader ->
                var line = reader.readLine()
                var n = 0L
                while (line != null) {
                    val a = line.indexOf(':')
                    val b = if (a > 0) line.indexOf(':', a + 1) else -1
                    if (b > a) {
                        val title = line.substring(b + 1)
                        if (WikiNamespaces.isArticle(title)) {
                            val off = line.substring(0, a).toLong()
                            val id = line.substring(a + 1, b).toLong()
                            builder.add(TextKey.keyBytes(title), encode(title, off, id))
                            kept++
                        }
                    }
                    if (++n % 50_000 == 0L) {
                        if (isCancelled()) { builder.close(); throw Cancelled() }
                        onProgress(0.8 * counting.count / total, kept)
                    }
                    line = reader.readLine()
                }
            }
            builder.finish(target) { merged -> onProgress(0.8 + 0.2 * merged / kept.coerceAtLeast(1), kept) }
            onProgress(1.0, kept)
            return kept
        }
    }
}

interface WikiSource : Closeable {
    /** Returns the page with [pageId] stored at [offset]; if [pageId] is null, matches [title]. */
    fun load(offset: Long, pageId: Long?, title: String? = null): WikiPage?
}

/** Reads Wikimedia "pages-articles-multistream.xml.bz2": each offset begins an independent bzip2 stream of ~100 pages. */
class MultistreamSource(file: File) : WikiSource {
    private val raf = RandomAccessFile(file, "r")
    private val cache = object : LinkedHashMap<Long, List<WikiPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<WikiPage>>?) = size > 6
    }

    @Synchronized
    fun stream(offset: Long): List<WikiPage> {
        cache[offset]?.let { return it }
        raf.channel.position(offset)
        val input = BufferedInputStream(Channels.newInputStream(raf.channel), 1 shl 16)
        val xml = BZip2CompressorInputStream(input, false).readBytes().toString(Charsets.UTF_8)
        val pages = WikiXml.parsePages(xml)
        cache[offset] = pages
        return pages
    }

    override fun load(offset: Long, pageId: Long?, title: String?): WikiPage? {
        val pages = stream(offset)
        return pages.firstOrNull { pageId != null && it.id == pageId } ?: pages.firstOrNull { title != null && it.title == title }
    }

    override fun close() = raf.close()
}

object WikiXml {
    fun parsePages(xml: String): List<WikiPage> {
        val out = ArrayList<WikiPage>(100)
        var i = 0
        while (true) {
            val start = xml.indexOf("<page>", i)
            if (start < 0) break
            val end = xml.indexOf("</page>", start)
            if (end < 0) break
            parsePage(xml, start, end)?.let { out.add(it) }
            i = end + 7
        }
        return out
    }

    private fun tag(xml: String, name: String, from: Int, to: Int): String? {
        val open = "<$name>"
        val s = xml.indexOf(open, from)
        if (s < 0 || s > to) return null
        val e = xml.indexOf("</$name>", s)
        if (e < 0 || e > to) return null
        return xml.substring(s + open.length, e)
    }

    private fun parsePage(xml: String, start: Int, end: Int): WikiPage? {
        val title = tag(xml, "title", start, end)?.let { unescape(it) } ?: return null
        val nsPos = xml.indexOf("<ns>", start)
        val id = tag(xml, "id", if (nsPos in start..end) nsPos else start, end)?.trim()?.toLongOrNull() ?: return null
        var redirect: String? = null
        val r = xml.indexOf("<redirect title=\"", start)
        if (r in start..end) {
            val q = xml.indexOf('"', r + 17)
            if (q > 0) redirect = unescape(xml.substring(r + 17, q))
        }
        var text = ""
        val t = xml.indexOf("<text", start)
        if (t in start..end) {
            val close = xml.indexOf('>', t)
            if (close > 0 && xml[close - 1] != '/') {
                val te = xml.indexOf("</text>", close)
                if (te in close..end) text = unescape(xml.substring(close + 1, te))
            }
        }
        return WikiPage(id, title, redirect, text)
    }

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "amp" -> "&"; ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}

/**
 * Ark's condensed Wikipedia format: one zstd block per original stream, holding cleaned wikitext.
 * Header "ARKWIKI1"; each block is [u32 length][zstd payload]; payload is a series of
 * [pageId][title][redirect][text] records.
 */
class CondensedSource(file: File) : WikiSource {
    private val raf = RandomAccessFile(file, "r")
    private val cache = object : LinkedHashMap<Long, List<WikiPage>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<WikiPage>>?) = size > 8
    }

    @Synchronized
    fun block(offset: Long): List<WikiPage> {
        cache[offset]?.let { return it }
        raf.seek(offset)
        val len = raf.readInt()
        val comp = ByteArray(len); raf.readFully(comp)
        val raw = Zstd.decompress(comp)
        val src = ByteSource(raw)
        val pages = ArrayList<WikiPage>(100)
        while (src.hasMore) {
            val id = src.varint(); val title = src.string(); val redirect = src.string(); val text = src.string()
            pages.add(WikiPage(id, title, redirect.ifEmpty { null }, text))
        }
        cache[offset] = pages
        return pages
    }

    override fun load(offset: Long, pageId: Long?, title: String?): WikiPage? {
        val pages = block(offset)
        return pages.firstOrNull { pageId != null && it.id == pageId } ?: pages.firstOrNull { title != null && it.title == title }
    }

    override fun close() = raf.close()

    companion object {
        val MAGIC = "ARKWIKI1".toByteArray()
        fun isCondensed(file: File): Boolean = try {
            FileInputStream(file).use { val b = ByteArray(8); it.read(b) == 8 && b.contentEquals(MAGIC) }
        } catch (e: Exception) { false }
    }
}

/** Thin wrapper so the core package doesn't depend on zstd-jni's API shape everywhere. */
object Zstd {
    fun decompress(data: ByteArray): ByteArray {
        val size = com.github.luben.zstd.Zstd.getFrameContentSize(data)
        require(size >= 0) { "bad zstd frame" }
        return com.github.luben.zstd.Zstd.decompress(data, size.toInt())
    }
    fun compress(data: ByteArray, level: Int): ByteArray = com.github.luben.zstd.Zstd.compress(data, level)
}
