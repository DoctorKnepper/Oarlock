package com.doctorknepper.ark

import com.doctorknepper.ark.core.MultistreamSource
import com.doctorknepper.ark.core.SortedIndex
import com.doctorknepper.ark.core.TextKey
import com.doctorknepper.ark.core.TitleIndex
import com.doctorknepper.ark.core.WikitextRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CoreTest {
    private val data = File("/home/claude/testdata")
    private val out = File(data, "out").apply { mkdirs() }

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
        <link rel="stylesheet" href="../../Ark/app/src/main/assets/web/article.css"></head><body><article><h1>$title</h1>$body</article></body></html>"""

    @Test
    fun sortedIndexRoundTrip() {
        val dir = File(out, "idx").apply { mkdirs() }
        val b = SortedIndex.ExternalBuilder(File(dir, "work"), runRecords = 1000)
        val words = (0 until 20000).map { "w" + Integer.toString(it * 7919 % 20000, 36) }
        for (w in words) b.add(w.toByteArray(), ("v" + w).toByteArray())
        val f = File(dir, "t.idx")
        b.finish(f)
        SortedIndex.open(f).use { idx ->
            val hits = idx.prefix("w1".toByteArray(), 5000)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.all { String(it.key).startsWith("w1") })
            assertEquals(words.count { it.startsWith("w1") }, hits.size)
            val ex = idx.exact("wabc".toByteArray())
            assertEquals(words.count { it == "wabc" }, ex.size)
        }
    }

    @Test
    fun renderSamples() {
        val raw = File(data, "raw")
        assumeTrue(raw.exists())
        for (f in raw.listFiles()!!.filter { it.name.endsWith(".wiki") }) {
            val t0 = System.nanoTime()
            val r = WikitextRenderer().render(f.nameWithoutExtension, f.readText())
            val ms = (System.nanoTime() - t0) / 1e6
            println("${f.name}: ${r.html.length} chars html, ${r.toc.size} toc, ${r.images.size} images, coords=${r.lat},${r.lon} in ${"%.1f".format(ms)} ms")
            File(out, f.nameWithoutExtension + ".html").writeText(page(f.nameWithoutExtension, r.html))
            for (bad in listOf("{{", "}}", "[[", "]]", "<ref", "&lt;ref")) {
                val i = r.html.indexOf(bad)
                if (i >= 0) println("LEFTOVER ${f.name} '$bad': " + r.html.substring(maxOf(0, i - 150), minOf(r.html.length, i + 150)).replace("\n", " "))
            }
        }
    }

    @Test
    fun simpleWikiDump() {
        val idx = File(data, "simplewiki-latest-pages-articles-multistream-index.txt.bz2")
        val dump = File(data, "simplewiki-latest-pages-articles-multistream.xml.bz2")
        assumeTrue(idx.exists() && dump.exists())
        val target = File(out, "simple-titles.idx")
        if (!target.exists()) {
            val t0 = System.currentTimeMillis()
            val n = TitleIndex.build(idx, target, File(out, "work"), { p, k -> }, { false })
            println("index: $n titles in ${System.currentTimeMillis() - t0} ms, ${target.length() / 1024} KB")
        }
        TitleIndex(target).use { ti ->
            val hits = ti.search("water pur", 10)
            println("search: " + hits.map { it.title })
            val hit = ti.resolve("Earth")
            assertNotNull(hit)
            MultistreamSource(dump).use { src ->
                val t0 = System.nanoTime()
                val p = src.load(hit!!.offset, hit.pageId)
                println("load Earth: ${(System.nanoTime() - t0) / 1e6} ms, ${p?.text?.length} chars")
                assertNotNull(p)
                val r = WikitextRenderer().render(p!!.title, p.text)
                File(out, "simple-Earth.html").writeText(page(p.title, r.html))
                for (t in listOf("United States", "First aid", "Mushroom", "Dog", "Canada", "Water")) {
                    val h = ti.resolve(t) ?: continue
                    var pg = src.load(h.offset, h.pageId) ?: continue
                    if (pg.redirect != null) { val h2 = ti.resolve(pg.redirect!!) ?: continue; pg = src.load(h2.offset, h2.pageId) ?: continue }
                    val rr = WikitextRenderer().render(pg.title, pg.text)
                    File(out, "simple-" + TextKey.normalize(t).replace(' ', '_') + ".html").writeText(page(pg.title, rr.html))
                }
                // random sample: no template/link leftovers
                var bad = 0
                val rnd = java.util.Random(7)
                repeat(300) {
                    val h = ti.random(rnd) ?: return@repeat
                    val pg = src.load(h.offset, h.pageId) ?: return@repeat
                    val rr = WikitextRenderer().render(pg.title, pg.text)
                    if (rr.html.contains("{{") || rr.html.contains("[[") || rr.html.contains("}}")) { bad++; if (bad < 6) println("leftover in ${pg.title}") }
                }
                println("random leftovers: $bad / 300")
            }
        }
    }
}
