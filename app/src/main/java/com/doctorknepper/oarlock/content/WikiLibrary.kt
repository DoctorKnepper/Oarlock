package com.doctorknepper.oarlock.content

import com.doctorknepper.oarlock.core.CondensedSource
import com.doctorknepper.oarlock.core.MultistreamSource
import com.doctorknepper.oarlock.core.RenderedArticle
import com.doctorknepper.oarlock.core.TitleHit
import com.doctorknepper.oarlock.core.TitleIndex
import com.doctorknepper.oarlock.core.WikiPage
import com.doctorknepper.oarlock.core.WikiSource
import com.doctorknepper.oarlock.core.WikitextRenderer
import com.doctorknepper.oarlock.data.InstalledPack
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.data.PackState
import com.doctorknepper.oarlock.data.PackStore
import java.io.File

/** Full-text Wikipedia packs built from Wikimedia dumps (full English and Simple English). */
class WikiLibrary(private val store: PackStore) {
    private class Opened(val pack: InstalledPack, val titles: TitleIndex, val source: WikiSource?, val stamp: String)
    private val opened = HashMap<String, Opened>()

    /** Wikipedia packs that can at least be searched, best first. */
    fun packs(): List<InstalledPack> = store.packs.value.values
        .filter { it.kind == PackKind.WIKIPEDIA && it.file(it.info.optString("titles", "titles.idx")).exists() }
        .sortedBy { if (it.id == "wikipedia") 0 else 1 }

    fun has() = packs().isNotEmpty()

    @Synchronized
    private fun open(p: InstalledPack): Opened? {
        val stamp = p.info.optString("format") + p.state + p.info.optLong("optimizedAt")
        opened[p.id]?.let { if (it.stamp == stamp) return it else { it.titles.close(); it.source?.close(); opened.remove(p.id) } }
        val titles = File(p.dir, p.info.optString("titles", "titles.idx"))
        if (!titles.exists()) return null
        val dump = File(p.dir, p.info.optString("dump", "dump.xml.bz2"))
        val src: WikiSource? = when {
            p.state != PackState.INSTALLED || !dump.exists() -> null
            CondensedSource.isCondensed(dump) -> CondensedSource(dump)
            else -> MultistreamSource(dump)
        }
        return Opened(p, TitleIndex(titles), src, stamp).also { opened[p.id] = it }
    }

    @Synchronized
    fun closeAll() { opened.values.forEach { it.titles.close(); it.source?.close() }; opened.clear() }

    @Synchronized
    fun close(id: String) { opened.remove(id)?.let { it.titles.close(); it.source?.close() } }

    fun search(query: String, limit: Int = 30): List<Hit> {
        val out = ArrayList<Hit>()
        for (p in packs()) {
            val o = open(p) ?: continue
            for (h in o.titles.search(query, limit)) out.add(Hit(p.id, p.title, h.title, url(p.id, h.title)))
            if (out.size >= limit) break
        }
        return out.distinctBy { it.title.lowercase() + it.source }
    }

    fun url(packId: String, title: String) = "https://ark.local/wiki/$packId/" + WikitextRenderer.urlEnc(title)

    sealed class Result {
        class Article(val page: WikiPage, val rendered: RenderedArticle, val redirectedFrom: String?) : Result()
        class NotYet(val title: String) : Result()
        class Missing(val title: String) : Result()
    }

    /** Loads and renders an article, following redirects. Searches other Wikipedia packs if this one lacks it. */
    fun article(packId: String?, rawTitle: String): Result {
        val order = packs().sortedBy { if (it.id == packId) 0 else 1 }
        var notYet = false
        for (p in order) {
            val o = open(p) ?: continue
            var hit: TitleHit = o.titles.resolve(rawTitle) ?: continue
            if (o.source == null) { notYet = true; continue }
            var page = o.source.load(hit.offset, hit.pageId, hit.title) ?: continue
            var from: String? = null
            var hops = 0
            while (page.redirect != null && hops++ < 4) {
                val target = page.redirect!!
                from = from ?: page.title
                hit = o.titles.resolve(target) ?: break
                page = o.source.load(hit.offset, hit.pageId, hit.title) ?: break
            }
            if (page.redirect != null && page.text.isBlank()) continue
            return Result.Article(page, WikitextRenderer(linkPrefix = "https://ark.local/wiki/${p.id}/").render(page.title, page.text), from)
        }
        return if (notYet) Result.NotYet(rawTitle) else Result.Missing(rawTitle)
    }

    fun random(): Pair<String, String>? {
        val p = packs().firstOrNull { it.state == PackState.INSTALLED } ?: return null
        val o = open(p) ?: return null
        val h = o.titles.random() ?: return null
        return p.id to h.title
    }

    /** True when [title] exists in some Wikipedia pack (used to style links to missing pages). */
    fun exists(title: String): Boolean = packs().any { p -> open(p)?.titles?.resolve(title) != null }
}
