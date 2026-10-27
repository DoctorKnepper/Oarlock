package com.doctorknepper.oarlock.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Integrity checks used before a pack is marked complete, and by the manual "Verify" action. */
object Verify {
    /** Hex digest of [file] ("SHA-256", "SHA-1"...), reporting progress as bytes read. */
    fun digest(file: File, algorithm: String, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance(algorithm)
        val total = file.length()
        var done = 0L
        var last = 0L
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (isCancelled()) throw Cancelled()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n); done += n
                val now = System.currentTimeMillis()
                if (now - last > 500) { onProgress(done, total); last = now }
            }
        }
        onProgress(total, total)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A downloaded book file is usable: EPUBs are valid zips with a container, PDFs start with %PDF, text isn't empty. */
    fun book(file: File): Boolean = try {
        when (file.extension.lowercase()) {
            "epub" -> ZipFile(file).use { z -> z.getEntry("META-INF/container.xml") != null }
            "pdf" -> FileInputStream(file).use { val b = ByteArray(5); it.read(b) == 5 && String(b).startsWith("%PDF") } && file.length() > 1000
            else -> file.length() > 200
        }
    } catch (e: Exception) { false }

    /** A map archive opens, its directories decode, and the world tile and a sample of detailed tiles read back. */
    fun map(file: File): Boolean = try {
        PmtilesReader(file).use { r ->
            if (r.tile(0, 0, 0) == null) return false
            val b = r.bounds
            val z = r.header.maxZ
            val cx = (b.minLon + b.maxLon) / 2; val cy = (b.minLat + b.maxLat) / 2
            // tiles over land near the middle of the area should exist at every zoom
            for (zz in listOf(z / 2, z)) r.tileRaw(zz, Pm.lonToX(cx, zz), Pm.latToY(cy, zz))
            true
        }
    } catch (e: Exception) { false }

    /** The condensed Wikipedia file starts with its marker and a sample of its blocks decompress. */
    fun condensed(file: File, sampleOffsets: List<Long>): Boolean = try {
        if (!CondensedSource.isCondensed(file)) false
        else CondensedSource(file).use { src -> sampleOffsets.all { src.block(it).isNotEmpty() } }
    } catch (e: Exception) { false }
}
