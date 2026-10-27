package com.doctorknepper.ark.content

import android.util.Log
import com.doctorknepper.ark.core.SortedIndex
import com.doctorknepper.ark.core.TextKey
import com.doctorknepper.ark.data.InstalledPack
import com.doctorknepper.ark.data.PackKind
import com.doctorknepper.ark.data.PackState
import com.doctorknepper.ark.data.PackStore
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import java.net.URLDecoder

data class Hit(val source: String, val sourceTitle: String, val title: String, val url: String, val snippet: String? = null)

/** Opens Kiwix ZIM packs with libzim and serves their pages, search and media. */
class ZimLibrary(private val store: PackStore) {
    private val archives = HashMap<String, Archive>()
    private val suggesters = HashMap<String, SuggestionSearcher>()
    private val imageIndex = HashMap<String, SortedIndex?>()

    companion object {
        @Volatile private var loaded = false
        fun loadNative(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("c++_shared"); System.loadLibrary("zim"); System.loadLibrary("zim_wrapper")
                loaded = true; true
            } catch (e: Throwable) { Log.e("Ark", "libzim failed to load", e); false }
        }

        fun verify(file: File): Boolean = try {
            loadNative()
            val a = Archive(file.path)
            val ok = a.entryCount > 0
            a.dispose(); ok
        } catch (e: Throwable) { false }
    }

    fun zimPacks(): List<InstalledPack> = store.packs.value.values.filter { it.kind == PackKind.ZIM && it.state == PackState.INSTALLED }

    @Synchronized
    fun archive(id: String): Archive? {
        archives[id]?.let { return it }
        val p = store[id] ?: return null
        if (p.kind != PackKind.ZIM || p.state != PackState.INSTALLED) return null
        if (!loadNative()) return null
        return try {
            Archive(p.file(p.info.optString("file")).path).also { archives[id] = it }
        } catch (e: Throwable) { Log.e("Ark", "open zim $id", e); null }
    }

    @Synchronized
    fun close(id: String) {
        suggesters.remove(id)?.dispose()
        archives.remove(id)?.dispose()
        imageIndex.remove(id)?.close()
    }

    fun mainPath(id: String): String? = try { archive(id)?.mainEntry?.getItem(true)?.path } catch (e: Throwable) { null }

    /** Returns (mimetype, bytes) for a path inside a pack, following redirects. */
    fun entry(id: String, rawPath: String): Pair<String, ByteArray>? {
        val a = archive(id) ?: return null
        val path = try { URLDecoder.decode(rawPath, "UTF-8") } catch (e: Exception) { rawPath }
        return try {
            synchronized(a) {
                val e = when {
                    a.hasEntryByPath(path) -> a.getEntryByPath(path)
                    a.hasEntryByPath(rawPath) -> a.getEntryByPath(rawPath)
                    a.hasEntryByTitle(path) -> a.getEntryByTitle(path)
                    else -> return null
                }
                val item = e.getItem(true)
                item.mimetype to item.data.data
            }
        } catch (e: Throwable) { null }
    }

    fun suggest(query: String, perPack: Int = 6): List<Hit> {
        val out = ArrayList<Hit>()
        for (p in zimPacks()) {
            val a = archive(p.id) ?: continue
            try {
                val s = synchronized(this) { suggesters.getOrPut(p.id) { SuggestionSearcher(a) } }
                synchronized(a) {
                    val search = s.suggest(query)
                    val it = search.getResults(0, perPack)
                    while (it.hasNext()) {
                        val r = it.next()
                        out.add(Hit(p.id, p.title, r.title, "https://ark.local/zim/${p.id}/${enc(r.path)}", if (r.hasSnippet()) r.snippet else null))
                    }
                }
            } catch (e: Throwable) { Log.w("Ark", "suggest failed in ${p.id}", e) }
        }
        return out
    }

    /** Full-text search in packs that ship a full-text index. */
    fun fullText(query: String, packId: String? = null, limit: Int = 25): List<Hit> {
        val out = ArrayList<Hit>()
        for (p in zimPacks()) {
            if (packId != null && p.id != packId) continue
            val a = archive(p.id) ?: continue
            try {
                synchronized(a) {
                    if (!a.hasFulltextIndex()) return@synchronized
                    val s = Searcher(a)
                    val search = s.search(Query(query))
                    val it = search.getResults(0, limit)
                    while (it.hasNext()) {
                        val e = it.next()
                        out.add(Hit(p.id, p.title, e.title, "https://ark.local/zim/${p.id}/${enc(e.path)}", null))
                    }
                    s.dispose()
                }
            } catch (e: Throwable) { Log.w("Ark", "fulltext failed in ${p.id}", e) }
        }
        return out
    }

    // ------------------------------------------------------------- Wikipedia images from Kiwix packs

    /** Packs whose media can illustrate full-text Wikipedia articles. */
    private fun imagePacks() = zimPacks().filter { it.info.optBoolean("images") }

    /**
     * Index of "File_name.jpg" -> path inside the ZIM. Built once per pack (a few seconds for
     * ~100k images) and stored next to the pack.
     */
    private fun images(p: InstalledPack): SortedIndex? = synchronized(imageIndex) {
        if (imageIndex.containsKey(p.id)) return imageIndex[p.id]
        val f = p.file("images.idx")
        val idx = try {
            if (!f.exists()) buildImageIndex(p, f)
            if (f.exists()) SortedIndex.open(f) else null
        } catch (e: Throwable) { Log.w("Ark", "image index ${p.id}", e); null }
        imageIndex[p.id] = idx
        idx
    }

    private fun buildImageIndex(p: InstalledPack, f: File) {
        val a = archive(p.id) ?: return
        val b = SortedIndex.ExternalBuilder(File(p.dir, "img-work"))
        synchronized(a) {
            for (prefix in listOf("_assets_/", "I/")) {
                val it = a.findByPath(prefix)
                var n = 0
                while (it.hasNext() && n < 2_000_000) {
                    val e = it.next(); n++
                    val path = e.path
                    val name = imageKey(path.substringAfterLast('/'))
                    if (name.isNotEmpty()) b.add(name.toByteArray(), path.toByteArray())
                }
            }
        }
        b.finish(f)
        File(p.dir, "img-work").deleteRecursively()
    }

    /** Normalizes a file name so wikitext names and ZIM asset names meet: decoded, no extension suffixes, lower case. */
    fun imageKey(raw: String): String {
        var n = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { raw }
        n = n.replace(' ', '_')
        for (suffix in listOf(".webp", ".png", ".jpg", ".jpeg", ".svg", ".gif")) {
            // ZIMs often store "Name.jpg.webp" or a thumbnail "Name.svg.png"
            if (n.lowercase().endsWith(suffix) && n.length > suffix.length + 1 && n.substring(0, n.length - suffix.length).contains('.')) n = n.substring(0, n.length - suffix.length)
        }
        return TextKey.normalize(n)
    }

    /** Finds an image for a wikitext file name in any installed illustrated pack. */
    fun wikiImage(fileName: String): Pair<String, ByteArray>? {
        val key = imageKey(fileName).toByteArray()
        for (p in imagePacks()) {
            val idx = images(p) ?: continue
            val rec = idx.exact(key, 1).firstOrNull() ?: continue
            entry(p.id, String(rec.value))?.let { return it }
        }
        return null
    }

    private fun enc(path: String) = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
