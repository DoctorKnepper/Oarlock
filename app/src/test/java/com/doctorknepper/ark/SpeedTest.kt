package com.doctorknepper.ark

import com.doctorknepper.ark.core.MultistreamSource
import com.doctorknepper.ark.core.WikiCondenser
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

class SpeedTest {
    @Test
    fun measure() {
        val dump = File("/home/claude/testdata/simplewiki-latest-pages-articles-multistream.xml.bz2")
        assumeTrue(dump.exists() && System.getenv("ARK_SPEED") != null)
        val t0 = System.currentTimeMillis()
        val inp = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(dump), 1 shl 16), true)
        val buf = ByteArray(1 shl 16); var total = 0L
        while (total < 400_000_000L) { val n = inp.read(buf); if (n < 0) break; total += n }
        val t1 = System.currentTimeMillis()
        println("bz2: ${total / 1_000_000} MB in ${t1 - t0} ms = ${total / 1000.0 / (t1 - t0)} MB/s")
        val src = MultistreamSource(dump)
        // condense speed over pages from first streams
        val idx = com.doctorknepper.ark.core.TitleIndex(File("/home/claude/testdata/out/simple-titles.idx"))
        var chars = 0L; var tc = 0L
        val rnd = java.util.Random(3)
        repeat(400) {
            val h = idx.random(rnd) ?: return@repeat
            val pages = src.stream(h.offset)
            val s = System.nanoTime()
            for (p in pages) { chars += p.text.length; WikiCondenser.condense(p.text) }
            tc += System.nanoTime() - s
        }
        println("condense: ${chars / 1_000_000} M chars in ${tc / 1_000_000} ms = ${chars / 1000.0 / (tc / 1e6)} Mchar/s")
    }
}
