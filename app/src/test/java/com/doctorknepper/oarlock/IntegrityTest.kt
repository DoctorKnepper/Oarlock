package com.doctorknepper.oarlock

import com.doctorknepper.oarlock.core.Verify
import com.doctorknepper.oarlock.engine.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class IntegrityTest {
    private val dir = File("/home/claude/testdata/integrity").apply { mkdirs() }

    @Test
    fun kiwixChecksumAndResume() {
        val k = Http.resolveKiwix("wikipedia_en_knots", "maxi")
        assertNotNull(k); println("kiwix: $k")
        assertNotNull(k!!.sha256)
        val f = File(dir, "knots.zim"); f.delete()
        val part = File(f.path + ".part")
        // simulate an interrupted download: first 3 MB, with a stale validator from a different file version
        Http.download(k.url, f, { _, _ -> }, { false })
        val full = f.readBytes()
        part.writeBytes(full.copyOfRange(0, 3_000_000))
        File(part.path + ".v").writeText("\"some-other-version\"")
        f.delete()
        Http.download(k.url, f, { _, _ -> }, { false })
        assertEquals(k.size, f.length())
        assertEquals(k.sha256, Verify.digest(f, "SHA-256"))
        // a genuine resume with the right validator
        part.writeBytes(full.copyOfRange(0, 5_000_000)); f.delete()
        Http.download(k.url, f, { _, _ -> }, { false })
        assertEquals(k.sha256, Verify.digest(f, "SHA-256"))
        // corruption is caught
        RandomAccessFile(f, "rw").use { it.seek(1234567); it.write(0x55) }
        assertTrue(Verify.digest(f, "SHA-256") != k.sha256)
    }

    @Test
    fun wikimediaDumpPinned() {
        val d = Http.resolveWikiDump("simplewiki")
        println("dump: $d")
        assertTrue(d.dumpUrl.contains("/simplewiki/20") && !d.dumpUrl.contains("latest"))
        assertTrue(Http.exists(d.dumpUrl) && Http.exists(d.indexUrl))
        assertEquals(40, d.dumpSha1.length)
        val idx = File(dir, "simple-index.bz2"); idx.delete()
        Http.download(d.indexUrl, idx, { _, _ -> }, { false })
        assertEquals(d.indexSha1, Verify.digest(idx, "SHA-1"))
        val en = Http.resolveWikiDump("enwiki")
        println("enwiki: ${en.date} ${en.dumpUrl}")
    }

    @Test
    fun mapAndBookChecks() {
        val hmb = File("/home/claude/testdata/out/hmb.pmtiles")
        if (hmb.exists()) assertTrue(Verify.map(hmb))
        val bad = File(dir, "bad.pmtiles"); bad.writeBytes(ByteArray(500))
        assertTrue(!Verify.map(bad))
        val epub = File(dir, "book.epub"); epub.delete()
        Http.download("https://www.gutenberg.org/ebooks/1653.epub.noimages", epub, { _, _ -> }, { false })
        assertTrue(Verify.book(epub))
        val fake = File(dir, "fake.epub"); fake.writeText("<html>Not found</html>".repeat(20))
        assertTrue(!Verify.book(fake))
    }
}
