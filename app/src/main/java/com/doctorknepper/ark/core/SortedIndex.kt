package com.doctorknepper.ark.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.PriorityQueue
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A compact, disk-resident sorted key/value index.
 *
 * Layout: MAGIC, then deflate-compressed blocks of records ([key][value], both length-prefixed),
 * then a block table (file offset + first key per block), then the table offset and MAGIC.
 * Only the block table is held in memory; lookups binary-search it and inflate one or two blocks.
 * Keys are compared as unsigned bytes and may repeat.
 */
class SortedIndex private constructor(private val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val blockOffsets: LongArray
    private val firstKeys: Array<ByteArray>
    private val tableOffset: Long
    val blockCount get() = blockOffsets.size

    init {
        val len = raf.length()
        raf.seek(len - 16)
        tableOffset = raf.readLong()
        val magic = ByteArray(8); raf.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Not an Ark index: ${file.name}" }
        raf.seek(tableOffset)
        val tableBytes = ByteArray((len - 16 - tableOffset).toInt())
        raf.readFully(tableBytes)
        val src = ByteSource(inflate(tableBytes))
        val n = src.int()
        blockOffsets = LongArray(n)
        firstKeys = Array(n) { EMPTY }
        var off = 0L
        for (i in 0 until n) {
            off += src.varint(); blockOffsets[i] = off
            firstKeys[i] = src.bytes()
        }
    }

    class Record(val key: ByteArray, val value: ByteArray)

    private val cache = object : LinkedHashMap<Int, List<Record>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Record>>?) = size > 24
    }

    @Synchronized
    fun block(i: Int): List<Record> {
        cache[i]?.let { return it }
        val start = blockOffsets[i]
        val end = if (i + 1 < blockOffsets.size) blockOffsets[i + 1] else tableOffset
        val bytes = ByteArray((end - start).toInt())
        raf.seek(start); raf.readFully(bytes)
        val src = ByteSource(inflate(bytes))
        val out = ArrayList<Record>(160)
        while (src.hasMore) out.add(Record(src.bytes(), src.bytes()))
        cache[i] = out
        return out
    }

    /** Index of the first block that may contain a key >= [key]. */
    private fun startBlock(key: ByteArray): Int {
        var lo = 0; var hi = firstKeys.size - 1; var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (compareBytes(firstKeys[mid], key) < 0) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Records whose key starts with [prefix], in key order. */
    fun prefix(prefix: ByteArray, limit: Int): List<Record> {
        if (firstKeys.isEmpty()) return emptyList()
        val out = ArrayList<Record>()
        var b = startBlock(prefix)
        while (b < blockCount && out.size < limit) {
            for (r in block(b)) {
                val c = compareBytes(r.key, prefix)
                if (r.key.startsWith(prefix)) { out.add(r); if (out.size >= limit) break }
                else if (c > 0) return out
            }
            b++
        }
        return out
    }

    fun exact(key: ByteArray, limit: Int = 32): List<Record> {
        if (firstKeys.isEmpty()) return emptyList()
        val out = ArrayList<Record>()
        var b = startBlock(key)
        while (b < blockCount) {
            for (r in block(b)) {
                val c = compareBytes(r.key, key)
                if (c == 0) { out.add(r); if (out.size >= limit) return out }
                else if (c > 0) return out
            }
            b++
        }
        return out
    }

    fun recordAt(block: Int, index: Int): Record? = block(block).getOrNull(index)

    override fun close() = raf.close()

    companion object {
        val MAGIC = "ARKIDX01".toByteArray()
        private val EMPTY = ByteArray(0)

        fun open(file: File) = SortedIndex(file)

        fun inflate(data: ByteArray): ByteArray {
            val inf = Inflater()
            inf.setInput(data)
            val out = ByteSink(data.size * 4)
            val buf = ByteArray(32 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            return out.toByteArray()
        }

        fun deflate(data: ByteArray, len: Int = data.size): ByteArray {
            val d = Deflater(7)
            d.setInput(data, 0, len); d.finish()
            val out = ByteSink(len / 2 + 64)
            val buf = ByteArray(32 * 1024)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            d.end()
            return out.toByteArray()
        }
    }

    /** Writes records that are already in sorted order. */
    class Writer(file: File, private val blockBytes: Int = 16 * 1024) : Closeable {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
        private var pos = 0L
        private val block = ByteSink(blockBytes + 1024)
        private var blockFirst: ByteArray? = null
        private val offsets = ArrayList<Long>()
        private val firsts = ArrayList<ByteArray>()
        var count = 0L; private set
        private var last: ByteArray? = null

        init { out.write(MAGIC); pos = MAGIC.size.toLong() }

        fun add(key: ByteArray, value: ByteArray) {
            last?.let { require(compareBytes(it, key) <= 0) { "keys out of order" } }
            last = key
            if (blockFirst == null) blockFirst = key
            block.bytes(key); block.bytes(value)
            count++
            if (block.size() >= blockBytes) flushBlock()
        }

        private fun flushBlock() {
            val first = blockFirst ?: return
            val comp = deflate(block.buffer(), block.size())
            offsets.add(pos); firsts.add(first)
            out.write(comp); pos += comp.size
            block.reset(); blockFirst = null
        }

        override fun close() {
            flushBlock()
            val table = ByteSink()
            table.varint(offsets.size)
            var prev = 0L
            for (i in offsets.indices) { table.varint(offsets[i] - prev); prev = offsets[i]; table.bytes(firsts[i]) }
            val comp = deflate(table.toByteArray())
            val tableOffset = pos
            out.write(comp)
            out.writeLong(tableOffset)
            out.write(MAGIC)
            out.close()
        }
    }

    /**
     * Accepts records in any order and produces a sorted index using bounded memory
     * (sorted runs on disk, then a k-way merge).
     */
    class ExternalBuilder(private val workDir: File, private val runRecords: Int = 250_000) : Closeable {
        private val buffer = ArrayList<Record>(runRecords)
        private val runs = ArrayList<File>()
        var added = 0L; private set

        init { workDir.mkdirs() }

        fun add(key: ByteArray, value: ByteArray) {
            buffer.add(Record(key, value)); added++
            if (buffer.size >= runRecords) spill()
        }

        private fun spill() {
            if (buffer.isEmpty()) return
            buffer.sortWith { a, b -> compareBytes(a.key, b.key) }
            val f = File(workDir, "run-${runs.size}.bin")
            DataOutputStream(BufferedOutputStream(FileOutputStream(f), 1 shl 16)).use { o ->
                for (r in buffer) {
                    o.writeInt(r.key.size); o.write(r.key); o.writeInt(r.value.size); o.write(r.value)
                }
            }
            runs.add(f); buffer.clear()
        }

        private class Run(file: File) : Closeable {
            val input = DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16))
            var current: Record? = null
            fun advance(): Boolean {
                current = try {
                    val k = ByteArray(input.readInt()); input.readFully(k)
                    val v = ByteArray(input.readInt()); input.readFully(v)
                    Record(k, v)
                } catch (e: EOFException) { null }
                return current != null
            }
            override fun close() = input.close()
        }

        fun finish(target: File, progress: (Long) -> Unit = {}) {
            spill()
            val tmp = File(target.path + ".part")
            Writer(tmp).use { w ->
                val pq = PriorityQueue<Run> { a, b -> compareBytes(a.current!!.key, b.current!!.key) }
                val opened = runs.map { Run(it) }
                try {
                    opened.forEach { if (it.advance()) pq.add(it) }
                    var n = 0L
                    while (pq.isNotEmpty()) {
                        val r = pq.poll()!!
                        val rec = r.current!!
                        w.add(rec.key, rec.value)
                        if (++n % 100_000 == 0L) progress(n)
                        if (r.advance()) pq.add(r)
                    }
                } finally { opened.forEach { it.close() } }
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "could not finalize index" }
            close()
        }

        override fun close() { runs.forEach { it.delete() }; runs.clear() }
    }
}
