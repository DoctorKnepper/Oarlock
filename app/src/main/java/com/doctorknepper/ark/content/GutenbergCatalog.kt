package com.doctorknepper.ark.content

import android.database.sqlite.SQLiteDatabase
import com.doctorknepper.ark.data.BookItem
import com.doctorknepper.ark.data.PackKind
import com.doctorknepper.ark.data.PackState
import com.doctorknepper.ark.data.PackStore
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class PgBook(val id: Int, val title: String, val author: String, val genres: String) {
    val item get() = BookItem("pg:$id", title, author, genres.substringBefore(';').trim())
}

/** Local, searchable copy of Project Gutenberg's catalog (English texts), grouped by genre. */
class GutenbergCatalog(private val store: PackStore) {
    private var db: SQLiteDatabase? = null
    private var dbPath: String? = null

    @Synchronized
    private fun open(): SQLiteDatabase? {
        val p = store["gutenberg-catalog"]?.takeIf { it.state == PackState.INSTALLED } ?: return null
        val f = p.file(p.info.optString("db", "catalog.db"))
        if (db != null && dbPath == f.path) return db
        db?.close()
        if (!f.exists()) return null
        db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        dbPath = f.path
        return db
    }

    fun available() = open() != null

    fun genres(): List<Pair<String, Int>> {
        val d = open() ?: return emptyList()
        d.rawQuery("SELECT name, COUNT(*) FROM genre GROUP BY name ORDER BY name", null).use { c ->
            val out = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
            return out
        }
    }

    fun byGenre(genre: String, limit: Int = 500): List<PgBook> {
        val d = open() ?: return emptyList()
        d.rawQuery("SELECT b.id, b.title, b.author, b.genres FROM genre g JOIN books b ON b.id = g.id WHERE g.name = ? ORDER BY b.title LIMIT $limit", arrayOf(genre)).use { c ->
            return generateSequence { if (c.moveToNext()) PgBook(c.getInt(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList()
        }
    }

    fun search(query: String, limit: Int = 60): List<PgBook> {
        val d = open() ?: return emptyList()
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { it.replace("\"", "") + "*" }
        if (terms.isEmpty()) return emptyList()
        return try {
            d.rawQuery("SELECT b.id, b.title, b.author, b.genres FROM fts JOIN books b ON b.id = fts.docid WHERE fts MATCH ? LIMIT $limit", arrayOf(terms)).use { c ->
                generateSequence { if (c.moveToNext()) PgBook(c.getInt(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList()
            }
        } catch (e: Exception) { emptyList() }
    }

    companion object {
        /** Builds the catalog database from pg_catalog.csv.gz. */
        fun build(gz: File, target: File, progress: (Double) -> Unit) {
            val tmp = File(target.path + ".tmp"); tmp.delete()
            val d = SQLiteDatabase.openOrCreateDatabase(tmp, null)
            d.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT, author TEXT, genres TEXT)")
            d.execSQL("CREATE TABLE genre(name TEXT, id INTEGER)")
            d.execSQL("CREATE VIRTUAL TABLE fts USING fts4(title, author, subjects)")
            val total = gz.length().toDouble()
            val counting = com.doctorknepper.ark.core.CountingInputStream(FileInputStream(gz))
            val reader = BufferedReader(InputStreamReader(GZIPInputStream(counting), Charsets.UTF_8), 1 shl 16)
            val ins = d.compileStatement("INSERT INTO books VALUES(?,?,?,?)")
            val insG = d.compileStatement("INSERT INTO genre VALUES(?,?)")
            val insF = d.compileStatement("INSERT INTO fts(docid,title,author,subjects) VALUES(?,?,?,?)")
            d.beginTransaction()
            try {
                val header = readRecord(reader) ?: return
                val col = header.withIndex().associate { it.value to it.index }
                var n = 0
                while (true) {
                    val r = readRecord(reader) ?: break
                    fun f(name: String) = col[name]?.let { r.getOrNull(it) } ?: ""
                    if (f("Language") != "en" || f("Type") != "Text") continue
                    val id = f("Text#").toIntOrNull() ?: continue
                    val title = f("Title").replace(Regex("\\s+"), " ").trim()
                    val author = f("Authors").split(';').firstOrNull()?.let { tidyAuthor(it) } ?: ""
                    val genres = f("Bookshelves").split(';').map { it.trim() }.filter { it.startsWith("Category:") }.map { it.removePrefix("Category:").trim() }
                    ins.bindLong(1, id.toLong()); ins.bindString(2, title); ins.bindString(3, author); ins.bindString(4, genres.joinToString("; ")); ins.executeInsert()
                    for (g in genres) { insG.bindString(1, g); insG.bindLong(2, id.toLong()); insG.executeInsert() }
                    insF.bindLong(1, id.toLong()); insF.bindString(2, title); insF.bindString(3, f("Authors")); insF.bindString(4, f("Subjects")); insF.executeInsert()
                    if (++n % 2000 == 0) progress(counting.count / total)
                }
                d.execSQL("CREATE INDEX genre_name ON genre(name)")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction(); reader.close(); d.close()
            }
            target.delete(); tmp.renameTo(target)
        }

        private fun tidyAuthor(a: String): String {
            var s = a.replace(Regex(",\\s*\\d.*$"), "").replace(Regex("\\s*\\[.*?\\]"), "").trim()
            if (s.contains(',')) {
                val (last, rest) = s.split(',', limit = 2)
                s = (rest.replace(Regex("\\(.*?\\)"), "").trim() + " " + last.trim()).trim()
            }
            return s
        }

        /** Reads one CSV record (RFC 4180, fields may span lines). */
        fun readRecord(r: BufferedReader): List<String>? {
            val out = ArrayList<String>()
            val field = StringBuilder()
            var inQuotes = false
            var any = false
            while (true) {
                val c = r.read()
                if (c < 0) { if (!any) return null; out.add(field.toString()); return out }
                any = true
                val ch = c.toChar()
                if (inQuotes) {
                    if (ch == '"') {
                        r.mark(1)
                        val n = r.read()
                        if (n == '"'.code) field.append('"') else { inQuotes = false; if (n >= 0) r.reset() }
                    } else field.append(ch)
                } else when (ch) {
                    '"' -> inQuotes = true
                    ',' -> { out.add(field.toString()); field.setLength(0) }
                    '\n' -> { out.add(field.toString()); return out }
                    '\r' -> {}
                    else -> field.append(ch)
                }
            }
        }
    }
}
