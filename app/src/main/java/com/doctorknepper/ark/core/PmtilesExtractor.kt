package com.doctorknepper.ark.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Copies the tiles inside one or more bounding boxes out of a remote PMTiles archive (for example the
 * Protomaps planet build) into a small local archive, using HTTP range requests. Only directories and
 * tiles that intersect the requested area are ever downloaded, and the job can resume after interruption.
 */
class PmtilesExtractor(
    private val http: OkHttpClient,
    private val url: String,
    private val areas: List<Bbox>,
    private val minZoom: Int,
    private val maxZoom: Int,
    private val target: File,
    private val userAgent: String,
) {
    class Progress(val fraction: Double, val bytesDownloaded: Long, val tiles: Long, val outputBytes: Long)

    private val work = File(target.path + ".work").apply { mkdirs() }
    private val dataFile = File(work, "data.bin")
    private val entriesFile = File(work, "entries.bin")
    private val stateFile = File(work, "state.json")
    @Volatile var downloaded = 0L; private set

    // ------------------------------------------------------------- HTTP

    private fun range(off: Long, len: Long): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", userAgent)
                    .header("Range", "bytes=$off-${off + len - 1}").build()
                http.newCall(req).execute().use { r ->
                    if (r.code != 206 && !(r.code == 200 && off == 0L)) throw IOException("HTTP ${r.code} for range request")
                    val body = r.body!!.bytes()
                    if (body.size.toLong() != len && r.code == 206) throw IOException("short read ${body.size}/$len")
                    downloaded += body.size
                    return if (r.code == 200) body.copyOf(len.toInt()) else body
                }
            } catch (e: IOException) {
                if (++attempt >= 6) throw e
                Thread.sleep(1000L * attempt * attempt)
            }
        }
    }

    // ------------------------------------------------------------- wanted tile-id ranges

    /** Sorted, merged [start, end) tile-id ranges covering every requested tile. */
    private fun wantedRanges(): Array<LongArray> {
        val rs = ArrayList<LongArray>()
        val coarse = 8
        for (z in minZoom..maxZoom) {
            val c = minOf(z, coarse)
            val base = Pm.zBase(z); val cbase = Pm.zBase(c)
            val mult = 1L shl (2 * (z - c))
            for (a in areas) {
                val x0 = Pm.lonToX(a.minLon, c); val x1 = Pm.lonToX(a.maxLon, c)
                val y0 = Pm.latToY(a.maxLat, c); val y1 = Pm.latToY(a.minLat, c)
                for (x in x0..x1) for (y in y0..y1) {
                    val h = Pm.zxyToId(c, x, y) - cbase
                    rs.add(longArrayOf(base + h * mult, base + (h + 1) * mult))
                }
            }
        }
        rs.sortBy { it[0] }
        val out = ArrayList<LongArray>()
        for (r in rs) {
            val last = out.lastOrNull()
            if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1]) else out.add(r.copyOf())
        }
        return out.toTypedArray()
    }

    private lateinit var wanted: Array<LongArray>

    private fun firstRangeAtOrAfter(id: Long): Int {
        var lo = 0; var hi = wanted.size - 1; var ans = wanted.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (wanted[mid][1] > id) { ans = mid; hi = mid - 1 } else lo = mid + 1
        }
        return ans
    }

    private fun intersects(a: Long, b: Long): Boolean {
        val i = firstRangeAtOrAfter(a)
        return i < wanted.size && wanted[i][0] < b
    }

    // ------------------------------------------------------------- output

    private lateinit var data: RandomAccessFile
    private lateinit var entries: DataOutputStream
    private var dataLen = 0L
    private var entryCount = 0L
    private var lastId = -1L
    private val dedup = object : LinkedHashMap<Long, Long>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 200_000
    }

    private fun addEntry(id: Long, run: Int, off: Long, len: Int) {
        if (id <= lastId) return
        entries.writeLong(id); entries.writeInt(run); entries.writeLong(off); entries.writeInt(len)
        entryCount++; lastId = id + run - 1
    }

    /** Pending tile copy: remote span (or an already-written local copy) -> wanted (tileId, runLength) segments. */
    private class Pending(val remoteOff: Long, val len: Int, val segs: List<LongArray>, val local: Long = -1)

    private var pendingBytes = 0L

    private fun flush(pending: MutableList<Pending>, dataOff: Long) {
        if (pending.isEmpty()) return
        // 1. fetch every span that is not already stored locally, grouping neighbours into one request
        val fetchIdx = pending.indices.filter { pending[it].local < 0 && dedup[pending[it].remoteOff] == null }
            .sortedBy { pending[it].remoteOff }
        val blobOf = HashMap<Int, Pair<ByteArray, Int>>()
        var i = 0
        while (i < fetchIdx.size) {
            var j = i
            val start = pending[fetchIdx[i]].remoteOff
            var end = start + pending[fetchIdx[i]].len
            while (j + 1 < fetchIdx.size) {
                val n = pending[fetchIdx[j + 1]]
                if (n.remoteOff - end < 32 * 1024 && n.remoteOff + n.len - start < 8L * 1024 * 1024) {
                    j++; end = maxOf(end, n.remoteOff + n.len)
                } else break
            }
            val blob = range(dataOff + start, end - start)
            for (k in i..j) blobOf[fetchIdx[k]] = blob to (pending[fetchIdx[k]].remoteOff - start).toInt()
            i = j + 1
        }
        // 2. write in tile-id order so the directory stays sorted
        for ((idx, p) in pending.withIndex()) {
            val local = if (p.local >= 0) p.local else dedup[p.remoteOff] ?: run {
                val (blob, at) = blobOf[idx] ?: return@run -1L
                val pos = dataLen
                data.seek(HEAD + pos); data.write(blob, at, p.len)
                dataLen += p.len
                if (p.len < 16 * 1024) dedup[p.remoteOff] = pos
                pos
            }
            if (local < 0) continue
            for (sg in p.segs) addEntry(sg[0], sg[1].toInt(), local, p.len)
        }
        pending.clear(); pendingBytes = 0
    }

    private fun handleTiles(dir: Pm.Dir, from: Int, to: Int, pending: MutableList<Pending>) {
        for (k in from until to) {
            val run = dir.runs[k]
            if (run == 0) continue
            val id = dir.ids[k]
            val end = id + run
            if (!intersects(id, end)) continue
            val segs = ArrayList<LongArray>(1)
            var r = firstRangeAtOrAfter(id)
            while (r < wanted.size && wanted[r][0] < end) {
                val st = maxOf(id, wanted[r][0]); val e = minOf(end, wanted[r][1])
                if (e > st) segs.add(longArrayOf(st, e - st))
                r++
            }
            if (segs.isEmpty()) continue
            val known = dedup[dir.offs[k]]
            pending.add(Pending(dir.offs[k], dir.lens[k], segs, known ?: -1))
            if (known == null) pendingBytes += dir.lens[k]
        }
    }

    // ------------------------------------------------------------- main

    fun run(onProgress: (Progress) -> Unit, isCancelled: () -> Boolean): File {
        val head = range(0, 16384)
        val h = Pm.Header.read(head)
        require(h.rootOff + h.rootLen <= head.size) { "unexpected root directory placement" }
        val root = Pm.decodeDir(head.copyOfRange(h.rootOff.toInt(), (h.rootOff + h.rootLen).toInt()), h.internalComp)
        wanted = wantedRanges()

        // Units of resumable work: index into the root directory.
        val state = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
        val sameSource = state.optString("url") == url && state.optLong("etag") == h.dataLen
        var startUnit = 0
        if (sameSource && dataFile.exists() && entriesFile.exists()) {
            startUnit = state.optInt("unit")
            dataLen = state.optLong("dataLen"); entryCount = state.optLong("entries"); lastId = state.optLong("lastId", -1)
            RandomAccessFile(dataFile, "rw").use { it.setLength(HEAD + dataLen) }
            RandomAccessFile(entriesFile, "rw").use { it.setLength(entryCount * 24) }
        } else {
            dataFile.delete(); entriesFile.delete(); dataLen = 0; entryCount = 0; lastId = -1
        }
        data = RandomAccessFile(dataFile, "rw")
        if (data.length() < HEAD) data.setLength(HEAD)
        entries = DataOutputStream(BufferedOutputStream(FileOutputStream(entriesFile, true), 1 shl 16))

        val units = (0 until root.size).filter { k ->
            val end = if (k + 1 < root.size) root.ids[k + 1] else Long.MAX_VALUE
            if (root.runs[k] == 0) intersects(root.ids[k], end) else intersects(root.ids[k], root.ids[k] + root.runs[k])
        }
        try {
            val pending = ArrayList<Pending>()
            var u = units.indexOfFirst { it >= startUnit }.let { if (it < 0) units.size else it }
            while (u < units.size) {
                if (isCancelled()) throw Cancelled()
                val k = units[u]
                if (root.runs[k] > 0) {
                    handleTiles(root, k, k + 1, pending); u++
                } else {
                    // batch consecutive leaf directories into one request
                    var j = u
                    val start = root.offs[k]
                    var end = start + root.lens[k]
                    while (j + 1 < units.size) {
                        val n = units[j + 1]
                        if (root.runs[n] != 0 || root.offs[n] != end || end - start > 4L * 1024 * 1024) break
                        j++; end = root.offs[n] + root.lens[n]
                    }
                    val blob = range(h.leafOff + start, end - start)
                    for (q in u..j) {
                        val kk = units[q]
                        val leaf = Pm.decodeDir(blob.copyOfRange((root.offs[kk] - start).toInt(), (root.offs[kk] - start + root.lens[kk]).toInt()), h.internalComp)
                        if (leaf.runs.any { it == 0 }) throw IOException("nested leaf directories are not supported")
                        handleTiles(leaf, 0, leaf.size, pending)
                        if (pending.size > 4000 || pendingBytes > 12L * 1024 * 1024) flush(pending, h.dataOff)
                    }
                    u = j + 1
                }
                flush(pending, h.dataOff)
                entries.flush()
                data.fd.sync()
                val nextUnit = if (u < units.size) units[u] else root.size
                stateFile.writeText(JSONObject().put("url", url).put("etag", h.dataLen).put("unit", nextUnit)
                    .put("dataLen", dataLen).put("entries", entryCount).put("lastId", lastId).toString())
                onProgress(Progress(u.toDouble() / units.size * 0.97, downloaded, entryCount, dataLen))
            }
        } finally {
            entries.close(); data.close()
        }
        val meta = try { String(Pm.gunzip(range(h.metaOff, h.metaLen))) } catch (e: Exception) { "{}" }
        finish(h, meta)
        onProgress(Progress(1.0, downloaded, entryCount, target.length()))
        work.deleteRecursively()
        return target
    }

    private class EntryReader(f: File) : AutoCloseable {
        private val inp = DataInputStream(java.io.BufferedInputStream(FileInputStream(f), 1 shl 16))
        fun next(e: LongArray): Boolean = try {
            e[0] = inp.readLong(); e[1] = inp.readInt().toLong(); e[2] = inp.readLong(); e[3] = inp.readInt().toLong(); true
        } catch (x: java.io.EOFException) { false }
        override fun close() = inp.close()
    }

    /** Builds directories by streaming the sorted entry file, so memory stays small for millions of tiles. */
    private fun finish(src: Pm.Header, metaJson: String) {
        // 1. merge consecutive runs that point at the same tile (ocean, empty land)
        val merged = File(work, "merged.bin")
        var m = 0L; var addressed = 0L; var contents = 0L; var maxOff = -1L
        DataOutputStream(BufferedOutputStream(FileOutputStream(merged), 1 shl 16)).use { out ->
            EntryReader(entriesFile).use { r ->
                val e = LongArray(4); val cur = LongArray(4); var has = false
                fun emit() {
                    out.writeLong(cur[0]); out.writeInt(cur[1].toInt()); out.writeLong(cur[2]); out.writeInt(cur[3].toInt())
                    m++; addressed += cur[1]
                    if (cur[2] > maxOff) { contents++; maxOff = cur[2] }
                }
                while (r.next(e)) {
                    if (has && cur[2] == e[2] && cur[3] == e[3] && cur[0] + cur[1] == e[0]) { cur[1] += e[1]; continue }
                    if (has) emit()
                    e.copyInto(cur); has = true
                }
                if (has) emit()
            }
        }
        // 2. directories: root only when it fits, otherwise leaves of growing size until the root fits
        val leafFile = File(work, "leaves.bin")
        var rootBytes: ByteArray? = null
        if (m <= 4096) {
            val all = readEntries(merged, m.toInt())
            val direct = Pm.encodeDir(all[0] as LongArray, all[1] as IntArray, all[2] as LongArray, all[3] as IntArray, 0, m.toInt())
            if (Pm.HEADER_LEN + direct.size <= Pm.ROOT_MAX) { rootBytes = direct; leafFile.writeBytes(ByteArray(0)) }
        }
        var leafSize = 4096
        while (rootBytes == null) {
            val ids = LongArray(leafSize); val runs = IntArray(leafSize); val offs = LongArray(leafSize); val lens = IntArray(leafSize)
            val rIds = ArrayList<Long>(); val rOffs = ArrayList<Long>(); val rLens = ArrayList<Int>()
            var leafPos = 0L
            FileOutputStream(leafFile).use { lf ->
                EntryReader(merged).use { r ->
                    val e = LongArray(4)
                    var n = 0
                    fun emitLeaf() {
                        if (n == 0) return
                        val leaf = Pm.encodeDir(ids, runs, offs, lens, 0, n)
                        rIds.add(ids[0]); rOffs.add(leafPos); rLens.add(leaf.size)
                        lf.write(leaf); leafPos += leaf.size; n = 0
                    }
                    while (r.next(e)) {
                        ids[n] = e[0]; runs[n] = e[1].toInt(); offs[n] = e[2]; lens[n] = e[3].toInt(); n++
                        if (n == leafSize) emitLeaf()
                    }
                    emitLeaf()
                }
            }
            val root = Pm.encodeDir(rIds.toLongArray(), IntArray(rIds.size), rOffs.toLongArray(), rLens.toIntArray(), 0, rIds.size)
            if (Pm.HEADER_LEN + root.size <= Pm.ROOT_MAX) rootBytes = root else leafSize *= 2
        }

        val meta = try {
            val o = JSONObject(metaJson)
            o.put("ark:areas", areas.joinToString(";") { "${it.minLon},${it.minLat},${it.maxLon},${it.maxLat}" })
            o.put("ark:maxzoom", maxZoom)
            Pm.gzip(o.toString().toByteArray())
        } catch (e: Exception) { Pm.gzip("{}".toByteArray()) }

        val minLon = areas.minOf { it.minLon }; val maxLon = areas.maxOf { it.maxLon }
        val minLat = areas.minOf { it.minLat }; val maxLat = areas.maxOf { it.maxLat }
        // 3. the data file already reserves the first 16 KB: append leaves + metadata, then write header + root.
        RandomAccessFile(dataFile, "rw").use { out ->
            out.setLength(HEAD + dataLen)
            val leafOff = HEAD + dataLen
            out.seek(leafOff)
            FileInputStream(leafFile).use { inp ->
                val buf = ByteArray(1 shl 16)
                while (true) { val r = inp.read(buf); if (r <= 0) break; out.write(buf, 0, r) }
            }
            val leafLen = leafFile.length()
            val metaOff = leafOff + leafLen
            out.write(meta)
            val header = Pm.Header(
                Pm.HEADER_LEN.toLong(), rootBytes!!.size.toLong(), metaOff, meta.size.toLong(), leafOff, leafLen,
                HEAD, dataLen, addressed, m, contents, 0, 2, src.tileComp, src.tileType,
                minZoom, maxZoom, (minLon * 1e7).toInt(), (minLat * 1e7).toInt(), (maxLon * 1e7).toInt(), (maxLat * 1e7).toInt(),
                minOf(maxZoom, 6), ((minLon + maxLon) / 2 * 1e7).toInt(), ((minLat + maxLat) / 2 * 1e7).toInt()
            )
            out.seek(0); out.write(header.write()); out.write(rootBytes!!)
            out.fd.sync()
        }
        if (target.exists()) target.delete()
        check(dataFile.renameTo(target)) { "could not finalize map file" }
    }

    private fun readEntries(f: File, n: Int): Array<Any> {
        val ids = LongArray(n); val runs = IntArray(n); val offs = LongArray(n); val lens = IntArray(n)
        EntryReader(f).use { r -> val e = LongArray(4); var i = 0; while (i < n && r.next(e)) { ids[i] = e[0]; runs[i] = e[1].toInt(); offs[i] = e[2]; lens[i] = e[3].toInt(); i++ } }
        return arrayOf(ids, runs, offs, lens)
    }

    companion object { const val HEAD = Pm.ROOT_MAX.toLong() }
}
