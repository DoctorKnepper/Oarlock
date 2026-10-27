package com.doctorknepper.ark

import com.doctorknepper.ark.core.CondensedSource
import com.doctorknepper.ark.core.TitleIndex
import com.doctorknepper.ark.core.WikiCondenser
import com.doctorknepper.ark.core.WikitextRenderer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CondenseTest {
    @Test
    fun condenseSimpleWiki() {
        val data = File("/home/claude/testdata")
        val dump = File(data, "simplewiki-latest-pages-articles-multistream.xml.bz2")
        val idx = File(data, "out/simple-titles.idx")
        assumeTrue(dump.exists() && idx.exists())
        val outData = File(data, "out/simple.arkwiki"); val outIdx = File(data, "out/simple-condensed.idx")
        outData.delete(); outIdx.delete()
        val t0 = System.currentTimeMillis()
        WikiCondenser(dump, idx, outData, outIdx, level = 12).run({ }, { false })
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        println("condensed ${dump.length() / 1_000_000} MB -> ${outData.length() / 1_000_000} MB (+index ${outIdx.length() / 1_000_000} MB) in $secs s; " +
            "input rate ${"%.1f".format(dump.length() / 1e6 / secs)} MB/s compressed")
        assertTrue(CondensedSource.isCondensed(outData))
        TitleIndex(outIdx).use { ti ->
            CondensedSource(outData).use { src ->
                for (t in listOf("Earth", "Water", "First aid", "Canada")) {
                    val h = ti.resolve(t); assertNotNull(h)
                    val p = src.load(h!!.offset, h.pageId); assertNotNull(p)
                    val html = WikitextRenderer().render(p!!.title, p.text).html
                    println("$t: ${p.text.length} chars wikitext -> ${html.length} html")
                    assertTrue(p.redirect != null || html.length > 500)
                }
            }
        }
    }
}
