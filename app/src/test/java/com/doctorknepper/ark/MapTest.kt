package com.doctorknepper.ark

import com.doctorknepper.ark.core.Bbox
import com.doctorknepper.ark.core.Pm
import com.doctorknepper.ark.core.PmtilesExtractor
import com.doctorknepper.ark.core.PmtilesReader
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class MapTest {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val url = System.getenv("ARK_PM_URL") ?: "https://build.protomaps.com/20261026.pmtiles"

    @Test
    fun hilbertMatchesSpec() {
        assertEquals(0L, Pm.zxyToId(0, 0, 0))
        assertEquals(1L, Pm.zxyToId(1, 0, 0))
        assertEquals(2L, Pm.zxyToId(1, 0, 1))
        assertEquals(3L, Pm.zxyToId(1, 1, 1))
        assertEquals(4L, Pm.zxyToId(1, 1, 0))
        assertEquals(5L, Pm.zxyToId(2, 0, 0))
    }

    @Test
    fun extractSmallArea() {
        val out = File("/home/claude/testdata/out/hmb.pmtiles")
        out.delete()
        val area = Bbox(-122.52, 37.42, -122.38, 37.54) // Half Moon Bay
        val t0 = System.currentTimeMillis()
        var last: PmtilesExtractor.Progress? = null
        PmtilesExtractor(http, url, listOf(area), 0, 14, out, "ArkOfflineLibrary/1.0 (test)").run({ last = it }, { false })
        println("extract: ${out.length() / 1024} KB, ${last?.tiles} entries, downloaded ${last?.bytesDownloaded} in ${System.currentTimeMillis() - t0} ms")
        PmtilesReader(out).use { r ->
            assertEquals(14, r.header.maxZ)
            val z = 14
            val x = Pm.lonToX(-122.43, z); val y = Pm.latToY(37.46, z)
            val t = r.tile(z, x, y)
            assertNotNull(t)
            println("tile z14 ${t!!.size} bytes")
            assertTrue(t.size > 100)
            assertNotNull(r.tile(0, 0, 0))
            assertNull(r.tile(14, Pm.lonToX(-74.0, z), Pm.latToY(40.7, z))) // New York is outside
            println("meta: " + r.metadata.take(200))
        }
        // world low zoom
        val world = File("/home/claude/testdata/out/world5.pmtiles")
        world.delete()
        PmtilesExtractor(http, url, listOf(Bbox(-180.0, -85.0, 180.0, 85.0)), 0, 5, world, "ArkOfflineLibrary/1.0 (test)").run({ last = it }, { false })
        PmtilesReader(world).use { r ->
            var n = 0
            for (x in 0L until 32) for (y in 0L until 32) if (r.tile(5, x, y) != null) n++
            println("world z5 tiles: $n, size ${world.length() / 1024} KB")
            assertEquals(1024, n)
        }
    }
}
