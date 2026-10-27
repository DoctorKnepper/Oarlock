package com.doctorknepper.ark.core

import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer

/** Small binary helpers shared by the index, tile and pack formats. */
object Varint {
    fun write(out: OutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun read(buf: ByteArray, pos: IntArray): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos[0]++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    fun read(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }
}

class ByteSink(initial: Int = 1024) : ByteArrayOutputStream(initial) {
    fun varint(v: Long) = Varint.write(this, v)
    fun varint(v: Int) = Varint.write(this, v.toLong())
    fun bytes(b: ByteArray) { varint(b.size); write(b) }
    fun string(s: String) = bytes(s.toByteArray(Charsets.UTF_8))
    fun buffer(): ByteArray = buf
}

class ByteSource(val buf: ByteArray, start: Int = 0, val end: Int = buf.size) {
    private val p = intArrayOf(start)
    val pos get() = p[0]
    val hasMore get() = p[0] < end
    fun varint(): Long = Varint.read(buf, p)
    fun int(): Int = varint().toInt()
    fun bytes(): ByteArray { val n = int(); val r = buf.copyOfRange(p[0], p[0] + n); p[0] += n; return r }
    fun string(): String { val n = int(); val s = String(buf, p[0], n, Charsets.UTF_8); p[0] += n; return s }
    fun skipBytes() { val n = int(); p[0] += n }
}

fun DataInput.readLongLE(): Long = java.lang.Long.reverseBytes(readLong())

fun compareBytes(a: ByteArray, b: ByteArray): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a[i].toInt() and 0xFF
        val y = b[i].toInt() and 0xFF
        if (x != y) return x - y
    }
    return a.size - b.size
}

fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

object TextKey {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("[\\s_]+")

    /** Search key: case-folded, accents removed, underscores/whitespace collapsed. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
        return marks.replace(decomposed, "").lowercase(java.util.Locale.ROOT).replace(spaces, " ").trim()
    }

    fun keyBytes(s: String) = normalize(s).toByteArray(Charsets.UTF_8)

    /** MediaWiki title canonical form: first letter upper case, underscores to spaces. */
    fun canonicalTitle(raw: String): String {
        var t = raw.replace('_', ' ').trim().replace(Regex(" {2,}"), " ")
        val hash = t.indexOf('#')
        if (hash >= 0) t = t.substring(0, hash).trim()
        if (t.isEmpty()) return t
        val cp = t.codePointAt(0)
        val up = String(Character.toChars(Character.toUpperCase(cp)))
        return up + t.substring(Character.charCount(cp))
    }
}

fun humanBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else if (v >= 10) "%.1f %s".format(v, units[i]) else "%.2f %s".format(v, units[i])
}
