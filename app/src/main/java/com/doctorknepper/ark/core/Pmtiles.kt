package com.doctorknepper.ark.core

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** PMTiles v3 primitives: Hilbert tile ids, header and directory (de)serialization. */
object Pm {
    const val HEADER_LEN = 127
    const val ROOT_MAX = 16384

    fun zBase(z: Int): Long = ((1L shl (2 * z)) - 1) / 3

    fun zxyToId(z: Int, x: Long, y: Long): Long {
        val acc = zBase(z)
        var tx = x; var ty = y
        var d = 0L
        var s = (1L shl z) shr 1
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s = s shr 1
        }
        return acc + d
    }

    fun idToZ(id: Long): Int {
        var z = 0
        while (zBase(z + 1) <= id) z++
        return z
    }

    fun lonToX(lon: Double, z: Int): Long {
        val n = 1L shl z
        return ((lon + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)
    }

    fun latToY(lat: Double, z: Int): Long {
        val n = 1L shl z
        val l = lat.coerceIn(-85.0511, 85.0511) * PI / 180
        return ((1 - ln(tan(l) + 1 / cos(l)) / PI) / 2 * n).toLong().coerceIn(0, n - 1)
    }

    class Header(
        var rootOff: Long, var rootLen: Long, var metaOff: Long, var metaLen: Long,
        var leafOff: Long, var leafLen: Long, var dataOff: Long, var dataLen: Long,
        var addressed: Long, var entries: Long, var contents: Long,
        var clustered: Int, var internalComp: Int, var tileComp: Int, var tileType: Int,
        var minZ: Int, var maxZ: Int, var minLonE7: Int, var minLatE7: Int, var maxLonE7: Int, var maxLatE7: Int,
        var centerZ: Int, var centerLonE7: Int, var centerLatE7: Int,
    ) {
        fun write(): ByteArray {
            val b = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            b.put("PMTiles".toByteArray()); b.put(3)
            for (v in longArrayOf(rootOff, rootLen, metaOff, metaLen, leafOff, leafLen, dataOff, dataLen, addressed, entries, contents)) b.putLong(v)
            b.put(clustered.toByte()); b.put(internalComp.toByte()); b.put(tileComp.toByte()); b.put(tileType.toByte())
            b.put(minZ.toByte()); b.put(maxZ.toByte())
            b.putInt(minLonE7); b.putInt(minLatE7); b.putInt(maxLonE7); b.putInt(maxLatE7)
            b.put(centerZ.toByte()); b.putInt(centerLonE7); b.putInt(centerLatE7)
            return b.array()
        }

        companion object {
            fun read(bytes: ByteArray): Header {
                require(String(bytes, 0, 7) == "PMTiles" && bytes[7].toInt() == 3) { "Not a PMTiles v3 archive" }
                val b = ByteBuffer.wrap(bytes, 8, HEADER_LEN - 8).order(ByteOrder.LITTLE_ENDIAN)
                val l = LongArray(11) { b.long }
                fun u8() = b.get().toInt() and 0xFF
                return Header(l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], l[9], l[10],
                    u8(), u8(), u8(), u8(), u8(), u8(), b.int, b.int, b.int, b.int, u8(), b.int, b.int)
            }
        }
    }

    /** Directory entries stored column-wise to keep large directories cheap. */
    class Dir(val ids: LongArray, val runs: IntArray, val offs: LongArray, val lens: IntArray) {
        val size get() = ids.size

        /** Index of entry covering [id], or -1. Leaf pointers (run 0) cover up to the next entry. */
        fun find(id: Long): Int {
            var lo = 0; var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = ids[mid]
                if (c == id) return mid
                if (c < id) lo = mid + 1 else hi = mid - 1
            }
            if (hi >= 0) {
                if (runs[hi] == 0) return hi
                if (id - ids[hi] < runs[hi]) return hi
            }
            return -1
        }
    }

    fun decodeDir(compressed: ByteArray, internalComp: Int): Dir {
        val raw = if (internalComp == 2) GZIPInputStream(ByteArrayInputStream(compressed)).readBytes() else compressed
        val p = intArrayOf(0)
        val n = Varint.read(raw, p).toInt()
        val ids = LongArray(n); val runs = IntArray(n); val lens = IntArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += Varint.read(raw, p); ids[i] = last }
        for (i in 0 until n) runs[i] = Varint.read(raw, p).toInt()
        for (i in 0 until n) lens[i] = Varint.read(raw, p).toInt()
        for (i in 0 until n) {
            val v = Varint.read(raw, p)
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return Dir(ids, runs, offs, lens)
    }

    fun encodeDir(ids: LongArray, runs: IntArray, offs: LongArray, lens: IntArray, from: Int, to: Int): ByteArray {
        val s = ByteSink((to - from) * 8 + 16)
        s.varint((to - from).toLong())
        var last = 0L
        for (i in from until to) { s.varint(ids[i] - last); last = ids[i] }
        for (i in from until to) s.varint(runs[i].toLong())
        for (i in from until to) s.varint(lens[i].toLong())
        for (i in from until to) {
            if (i > from && offs[i] == offs[i - 1] + lens[i - 1]) s.varint(0L) else s.varint(offs[i] + 1)
        }
        return gzip(s.toByteArray())
    }

    fun gzip(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size / 2 + 32)
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    fun gunzip(b: ByteArray): ByteArray =
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(b)).readBytes() else b
}

data class Bbox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

/** Random-access reader for a local PMTiles archive. */
class PmtilesReader(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val header: Pm.Header
    private val root: Pm.Dir
    private val leafCache = object : LinkedHashMap<Long, Pm.Dir>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pm.Dir>?) = size > 64
    }
    var metadata: String = "{}"; private set

    init {
        val h = ByteArray(Pm.HEADER_LEN); raf.seek(0); raf.readFully(h)
        header = Pm.Header.read(h)
        root = Pm.decodeDir(read(header.rootOff, header.rootLen.toInt()), header.internalComp)
        if (header.metaLen in 1..2_000_000) {
            val m = read(header.metaOff, header.metaLen.toInt())
            metadata = String(if (header.internalComp == 2) Pm.gunzip(m) else m)
        }
    }

    @Synchronized
    private fun read(off: Long, len: Int): ByteArray { val b = ByteArray(len); raf.seek(off); raf.readFully(b); return b }

    @Synchronized
    private fun leaf(off: Long, len: Int): Pm.Dir = leafCache.getOrPut(off) {
        Pm.decodeDir(read(header.leafOff + off, len), header.internalComp)
    }

    /** Raw (still compressed) tile bytes, or null when the archive has no such tile. */
    fun tileRaw(z: Int, x: Long, y: Long): ByteArray? {
        if (z < header.minZ || z > header.maxZ) return null
        val id = Pm.zxyToId(z, x, y)
        var dir = root
        repeat(4) {
            val i = dir.find(id)
            if (i < 0) return null
            if (dir.runs[i] > 0) return read(header.dataOff + dir.offs[i], dir.lens[i])
            dir = leaf(dir.offs[i], dir.lens[i])
        }
        return null
    }

    /** Decompressed tile bytes (vector tiles are gzip-compressed inside Protomaps archives). */
    fun tile(z: Int, x: Long, y: Long): ByteArray? = tileRaw(z, x, y)?.let { if (header.tileComp == 2) Pm.gunzip(it) else it }

    val bounds get() = Bbox(header.minLonE7 / 1e7, header.minLatE7 / 1e7, header.maxLonE7 / 1e7, header.maxLatE7 / 1e7)

    override fun close() = raf.close()
}
