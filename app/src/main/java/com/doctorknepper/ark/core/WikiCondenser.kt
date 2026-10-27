package com.doctorknepper.ark.core

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * "Optimize": rewrites the Wikipedia dump into Ark's condensed format. Every article keeps its full
 * readable text, but citations, maintenance tags, navigation boxes, categories, interlanguage links,
 * comments and reference sections are removed, non-article pages are dropped, and each block is
 * recompressed with zstd. Resumable: progress is checkpointed every few hundred blocks.
 */
class WikiCondenser(
    private val dump: File,
    private val titleIndex: File,
    private val outData: File,
    private val outIndex: File,
    private val level: Int = 12,
) {
    class Progress(val fraction: Double, val inputBlocks: Int, val totalBlocks: Int, val outputBytes: Long)

    private val work = File(outData.path + ".work").apply { mkdirs() }
    private val mapFile = File(work, "offsets.bin")
    private val stateFile = File(work, "state.json")

    fun run(onProgress: (Progress) -> Unit, isCancelled: () -> Boolean) {
        // Stream offsets that hold at least one article, in file order.
        val offsets = SortedIndex.open(titleIndex).use { idx ->
            val set = java.util.TreeSet<Long>()
            for (b in 0 until idx.blockCount) for (r in idx.block(b)) set.add(ByteSource(r.value).let { it.skipBytes(); it.varint() })
            set.toLongArray()
        }
        val state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        var done = 0
        var outLen = CondensedSource.MAGIC.size.toLong()
        if (state.optLong("dumpSize") == dump.length() && outData.exists() && mapFile.exists()) {
            done = state.optInt("done"); outLen = state.optLong("outLen")
            RandomAccessFile(mapFile, "rw").use { it.setLength(done * 16L) }
        } else {
            mapFile.delete()
            FileOutputStream(outData).use { it.write(CondensedSource.MAGIC) }
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        // Each worker opens its own reader; a bzip2 stream is independent, so blocks condense in parallel.
        val readers = ThreadLocal.withInitial { MultistreamSource(dump) }
        val opened = java.util.Collections.synchronizedList(ArrayList<MultistreamSource>())
        try {
            RandomAccessFile(outData, "rw").use { out ->
                out.setLength(outLen); out.seek(outLen)
                DataOutputStream(BufferedOutputStream(FileOutputStream(mapFile, true))).use { map ->
                    var i = done
                    val window = threads * 8
                    while (i < offsets.size) {
                        if (isCancelled()) throw Cancelled()
                        val end = minOf(offsets.size, i + window)
                        val futures = (i until end).map { k ->
                            pool.submit<ByteArray> {
                                val r = readers.get().also { if (it !in opened) opened.add(it) }
                                val pages = r.stream(offsets[k])
                                val sink = ByteSink(256 * 1024)
                                for (p in pages) {
                                    if (!WikiNamespaces.isArticle(p.title)) continue
                                    sink.varint(p.id); sink.string(p.title); sink.string(p.redirect ?: "")
                                    sink.string(if (p.redirect != null) "" else condense(p.text))
                                }
                                Zstd.compress(sink.toByteArray(), level)
                            }
                        }
                        for ((n, f) in futures.withIndex()) {
                            val comp = f.get()
                            val at = outLen
                            out.writeInt(comp.size); out.write(comp)
                            outLen += 4 + comp.size
                            map.writeLong(offsets[i + n]); map.writeLong(at)
                        }
                        i = end
                        map.flush(); out.fd.sync()
                        stateFile.writeText(JSONObject().put("dumpSize", dump.length()).put("done", i).put("outLen", outLen).toString())
                        onProgress(Progress(0.95 * i / offsets.size, i, offsets.size, outLen))
                    }
                }
            }
        } finally {
            pool.shutdownNow()
            opened.forEach { runCatching { it.close() } }
        }
        // Rewrite the title index with the new block offsets (records stay in the same sorted order).
        val n = (mapFile.length() / 16).toInt()
        val oldOffs = LongArray(n); val newOffs = LongArray(n)
        DataInputStream(java.io.BufferedInputStream(FileInputStream(mapFile))).use { d ->
            for (k in 0 until n) { oldOffs[k] = d.readLong(); newOffs[k] = d.readLong() }
        }
        val tmp = File(outIndex.path + ".part")
        SortedIndex.open(titleIndex).use { idx ->
            SortedIndex.Writer(tmp).use { w ->
                for (b in 0 until idx.blockCount) {
                    for (r in idx.block(b)) {
                        val s = ByteSource(r.value)
                        val title = s.string(); val off = s.varint(); val id = s.varint()
                        val k = java.util.Arrays.binarySearch(oldOffs, off)
                        if (k < 0) continue
                        w.add(r.key, TitleIndex.encode(title, newOffs[k], id))
                    }
                }
            }
        }
        if (outIndex.exists()) outIndex.delete()
        check(tmp.renameTo(outIndex))
        work.deleteRecursively()
        onProgress(Progress(1.0, offsets.size, offsets.size, outLen))
    }

    companion object {
        private val COMMENT = Regex("<!--.*?(-->|$)", RegexOption.DOT_MATCHES_ALL)
        private val REF_SELF = Regex("<ref(\\s[^>]*)?/\\s*>", RegexOption.IGNORE_CASE)
        private val REF_BLOCK = Regex("<ref(\\s[^>]*)?>.*?</ref\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val REFS = Regex("<references(\\s[^>]*)?(/>|>.*?</references\\s*>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val CATEGORY = Regex("\\[\\[\\s*(Category|Kategorie)\\s*:[^\\]]*\\]\\]", RegexOption.IGNORE_CASE)
        private val INTERLANG = Regex("\\[\\[[a-z]{2,3}(-[a-z]+)?:[^\\]|]*\\]\\]")
        private val BLANKS = Regex("\\n{3,}")
        private val TRAIL_SPACE = Regex("[ \\t]+\\n")
        private val HEADING = Regex("^(={2,6})\\s*(.+?)\\s*\\1\\s*$")
        private val tail = setOf(
            "references", "notes", "footnotes", "citations", "sources", "bibliography", "external links", "further reading",
            "notes and references", "references and notes", "explanatory notes", "general and cited sources", "works cited",
            "cited sources", "general references", "specific references", "inline citations", "online sources", "endnotes"
        )

        fun condense(text: String): String {
            var t = COMMENT.replace(text, "")
            t = REF_SELF.replace(t, "")
            t = REF_BLOCK.replace(t, "")
            t = REFS.replace(t, "")
            t = WikiScan.mapTemplates(t) { tpl ->
                val k = tpl.key
                if (k.startsWith("cite") || (TemplateRules.isDropped(k) && !TemplateRules.isInfobox(k) && k !in keepEvenIfDropped)) ""
                else "{{" + tpl.raw + "}}"
            }
            t = CATEGORY.replace(t, "")
            t = INTERLANG.replace(t, "")
            // drop trailing reference/link sections
            val sb = StringBuilder(t.length)
            var skip = 0
            for (line in t.split('\n')) {
                val h = HEADING.matchEntire(line.trim())
                if (h != null) {
                    val lvl = h.groupValues[1].length
                    if (skip > 0 && lvl <= skip) skip = 0
                    if (skip == 0 && h.groupValues[2].replace("'", "").trim().lowercase() in tail) { skip = lvl; continue }
                }
                if (skip == 0) sb.append(line).append('\n')
            }
            t = TRAIL_SPACE.replace(sb.toString(), "\n")
            return BLANKS.replace(t, "\n\n").trim()
        }

        private val keepEvenIfDropped = setOf("chem2", "sic")
    }
}
