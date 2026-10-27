package com.doctorknepper.oarlock.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.doctorknepper.oarlock.core.Cancelled
import com.doctorknepper.oarlock.core.PmtilesExtractor
import com.doctorknepper.oarlock.core.SortedIndex
import com.doctorknepper.oarlock.core.ByteSource
import com.doctorknepper.oarlock.core.TitleIndex
import com.doctorknepper.oarlock.core.Verify
import com.doctorknepper.oarlock.core.WikiCondenser
import com.doctorknepper.oarlock.core.humanBytes
import com.doctorknepper.oarlock.data.BookItem
import com.doctorknepper.oarlock.data.Catalog
import com.doctorknepper.oarlock.data.InstalledPack
import com.doctorknepper.oarlock.data.PackDef
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.data.PackState
import com.doctorknepper.oarlock.data.PackStore
import com.doctorknepper.oarlock.data.Settings
import com.doctorknepper.oarlock.data.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One unit of queued work. [def] is stored for packs that aren't in the catalog (custom maps, single books).
 * [action] is "install", "optimize", "verify" or "move:<destination root path>".
 */
data class Job(val packId: String, val defcon: Boolean, val def: PackDef? = null, val action: String = "install")

data class EngineState(
    val running: Boolean = false,
    val current: String? = null,
    val currentAction: String = "install",
    val currentTitle: String = "",
    val step: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val bytesPerSec: Long = 0,
    val queue: List<Job> = emptyList(),
    val notes: Map<String, String> = emptyMap(),   // per-pack messages: skipped / failed / verified
    val defconActive: Boolean = false,
    val waitingForWifi: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Runs the download queue one job at a time, in order. It is driven by [DownloadService] so it keeps
 * working with the screen off, and it persists the queue so work resumes after a restart or reboot.
 * Nothing is marked complete until it has been checked: sizes and published checksums for downloads,
 * and a structural read-back for maps and books.
 */
class Engine(
    private val context: Context,
    private val catalog: Catalog,
    private val store: PackStore,
    private val storage: Storage,
    private val settings: Settings,
    private val checkZim: (File, Boolean) -> Boolean,
    private val buildGutenberg: (File, File, (Double) -> Unit) -> Unit,
    private val release: (String) -> Unit,
) {
    private val queueFile = File(context.filesDir, "queue.json")
    private val _state = MutableStateFlow(EngineState(queue = loadQueue()))
    val state: StateFlow<EngineState> = _state
    @Volatile private var cancelCurrent = false
    @Volatile private var stopAll = false

    // ------------------------------------------------------------- queue management

    private fun loadQueue(): List<Job> = try {
        val a = JSONArray(queueFile.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val def = o.optJSONObject("def")?.let { d ->
                PackDef(d.getString("id"), PackKind.valueOf(d.getString("kind").uppercase()), d.getString("title"), d.optString("desc"), d.optString("category"), d.optLong("size"), d)
            }
            Job(o.getString("id"), o.optBoolean("defcon"), def, o.optString("action", "install"))
        }
    } catch (e: Exception) { emptyList() }

    private fun saveQueue(q: List<Job>) {
        val a = JSONArray()
        q.forEach { j -> a.put(JSONObject().put("id", j.packId).put("defcon", j.defcon).put("action", j.action).apply { j.def?.let { put("def", it.json) } }) }
        val tmp = File(queueFile.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(queueFile)
    }

    private fun update(f: (EngineState) -> EngineState) { _state.value = f(_state.value) }

    fun defOf(job: Job): PackDef? = job.def ?: catalog[job.packId] ?: store[job.packId]?.let { p ->
        PackDef(p.id, p.kind, p.title, "", "", p.bytes, p.info.optJSONObject("def") ?: JSONObject().put("id", p.id).put("kind", p.kind.name).put("title", p.title))
    }

    fun enqueue(ids: List<String>, defcon: Boolean = false) {
        val existing = _state.value.queue.filter { it.action == "install" }.map { it.packId }.toSet()
        val add = ids.filter { it !in existing && !store.isInstalled(it) && catalog[it] != null }.map { Job(it, defcon) }
        val q = if (defcon) add + _state.value.queue.filter { it.packId !in ids } else _state.value.queue + add
        saveQueue(q)
        update { it.copy(queue = q, defconActive = it.defconActive || defcon, notes = it.notes - ids.toSet()) }
    }

    fun enqueueCustom(def: PackDef) {
        val q = _state.value.queue.filter { it.packId != def.id } + Job(def.id, false, def)
        saveQueue(q); update { it.copy(queue = q) }
    }

    private fun enqueueAction(id: String, action: String) {
        if (_state.value.queue.any { it.packId == id && it.action == action }) return
        val q = _state.value.queue + Job(id, false, null, action)
        saveQueue(q); update { it.copy(queue = q, notes = it.notes - id) }
    }

    fun enqueueOptimize(id: String) = enqueueAction(id, "optimize")
    fun enqueueVerify(id: String) = enqueueAction(id, "verify")
    fun enqueueMove(id: String, destinationRoot: String) = enqueueAction(id, "move:$destinationRoot")

    fun remove(id: String) {
        if (_state.value.current == id) cancelCurrent = true
        val q = _state.value.queue.filter { it.packId != id }
        saveQueue(q); update { it.copy(queue = q) }
    }

    fun move(id: String, delta: Int) {
        val q = _state.value.queue.toMutableList()
        val i = q.indexOfFirst { it.packId == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= q.size) return
        q.add(j, q.removeAt(i)); saveQueue(q); update { it.copy(queue = q) }
    }

    fun stop() { stopAll = true; cancelCurrent = true }

    val hasWork get() = _state.value.queue.isNotEmpty()

    fun clearNote(id: String) = note(id, null)

    // ------------------------------------------------------------- cleanup after interruptions

    /**
     * Removes leftovers that would otherwise silently use space: folders of packs that never got going,
     * temporary copies from an Optimize run that is no longer queued, and stray index build folders.
     * Returns the number of bytes freed.
     */
    fun cleanup(): Long {
        var freed = 0L
        val queued = _state.value.queue
        fun gone(f: File) { if (f.exists()) { freed += storage.dirSize(f); f.deleteRecursively() } }
        for (v in storage.volumes()) {
            for (d in v.packsDir.listFiles() ?: continue) {
                if (!d.isDirectory) continue
                val id = d.name
                val q = queued.filter { it.packId == id }
                if (!File(d, "pack.json").exists() && q.isEmpty()) { gone(d); continue }
                if (q.none { it.action == "optimize" }) {
                    gone(File(d, "articles.arkwiki.work")); gone(File(d, "titles-condensed.idx"))
                    val p = store[id]
                    if (p?.info?.optString("format") != "condensed") gone(File(d, "articles.arkwiki"))
                }
                if (q.none { it.action == "install" }) { gone(File(d, "work")); gone(File(d, "img-work")) }
                // A move that was interrupted leaves an incomplete copy marked with ".moving".
                if (File(d, ".moving").exists() && q.none { it.action.startsWith("move:") }) gone(d)
            }
        }
        if (freed > 0) store.refresh()
        return freed
    }

    // ------------------------------------------------------------- runner

    /** Processes the queue until it's empty or [stop] is called. Blocking; call from a background thread. */
    fun runQueue(onTick: () -> Unit) {
        stopAll = false
        update { it.copy(running = true) }
        try {
            while (!stopAll) {
                val job = _state.value.queue.firstOrNull() ?: break
                val def = defOf(job)
                if (def == null) { dropFromQueue(job); continue }
                cancelCurrent = false
                update { it.copy(current = job.packId, currentAction = job.action, currentTitle = def.title, step = "Starting", done = 0, total = 0, bytesPerSec = 0) }
                val needsNet = job.action == "install"
                val waitReason = if (needsNet) networkProblem(def, job) else null
                if (waitReason != null) {
                    // run something else that can go ahead (smaller pack, or offline work) instead of waiting
                    val q = _state.value.queue
                    val other = q.firstOrNull { o -> o !== job && (o.action != "install" || defOf(o)?.let { networkProblem(it, o) == null } == true) }
                    if (other != null) {
                        val nq = listOf(other) + q.filter { it !== other }
                        saveQueue(nq); update { it.copy(queue = nq) }
                        continue
                    }
                    update { it.copy(step = waitReason, waitingForWifi = true) }
                    onTick()
                    var waited = 0
                    while (!stopAll && waited < 30) { Thread.sleep(1000); waited++ }
                    continue
                }
                update { it.copy(waitingForWifi = false) }
                try {
                    when {
                        job.action == "optimize" -> optimize(job, onTick)
                        job.action == "verify" -> verify(job.packId, onTick)
                        job.action.startsWith("move:") -> movePack(job.packId, job.action.removePrefix("move:"), onTick)
                        else -> { install(job, def, onTick); note(job.packId, null) }
                    }
                    dropFromQueue(job)
                } catch (e: Cancelled) {
                    if (stopAll) break
                    dropFromQueue(job)
                } catch (e: SkipPack) {
                    note(job.packId, e.message)
                    dropFromQueue(job)
                } catch (e: Exception) {
                    note(job.packId, "Failed: " + (e.message ?: e.javaClass.simpleName) + if (job.action == "install") ". Tap download to resume." else "")
                    dropFromQueue(job)
                }
                store.refresh()
            }
        } finally {
            update { it.copy(running = false, current = null, step = "", defconActive = if (it.queue.isEmpty()) false else it.defconActive) }
        }
    }

    private fun dropFromQueue(job: Job) {
        val q = _state.value.queue.filter { it !== job && !(it.packId == job.packId && it.action == job.action) }
        saveQueue(q); update { it.copy(queue = q) }
    }

    private fun note(id: String, msg: String?) = update { s -> s.copy(notes = if (msg == null) s.notes - id else s.notes + (id to msg)) }

    class SkipPack(msg: String) : Exception(msg)

    private fun networkProblem(def: PackDef, job: Job): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Waiting for an internet connection"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "Waiting for an internet connection"
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (metered && settings.wifiOnlyLarge.value && !job.defcon && def.size > 1_000_000_000L) return "Waiting for Wi-Fi (large pack)"
        return null
    }

    private fun checkSpace(def: PackDef, dir: File, already: Long) {
        val vol = storage.volumeOf(dir) ?: storage.current()
        val need = def.size + def.extraSpace - already
        val free = vol.free() - storage.reserve
        if (need > free) throw SkipPack("Skipped: needs ${humanBytes(need)}, only ${humanBytes(maxOf(0, free))} free (keeping ${humanBytes(storage.reserve)} for your phone)")
    }

    private fun packDirFor(id: String): File {
        // keep using the folder of a partial download wherever it is
        store[id]?.let { return it.dir }
        for (v in storage.volumes()) { val d = File(v.packsDir, id); if (d.exists()) return d }
        return storage.packDir(storage.current(), id)
    }

    private val speed = SpeedMeter()

    private fun progress(step: String, done: Long, total: Long, onTick: () -> Unit) {
        speed.add(done)
        update { it.copy(step = step, done = done, total = total, bytesPerSec = speed.rate()) }
        onTick()
    }

    private fun checking(step: String, onTick: () -> Unit): (Long, Long) -> Unit {
        speed.reset()
        return { d, t -> progress(step, d, t, onTick) }
    }

    private val cancelled = { cancelCurrent || stopAll }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // ------------------------------------------------------------- installers

    private fun install(job: Job, def: PackDef, onTick: () -> Unit) {
        val dir = packDirFor(def.id)
        checkSpace(def, dir, storage.dirSize(dir))
        dir.mkdirs()
        speed.reset()
        when (def.kind) {
            PackKind.ZIM -> installZim(def, dir, onTick)
            PackKind.WIKIPEDIA -> installWikipedia(def, dir, onTick)
            PackKind.MAP, PackKind.CUSTOM_MAP -> installMap(def, dir, onTick)
            PackKind.BOOKS -> installBooks(def, dir, onTick)
            PackKind.GUTENBERG -> installGutenberg(def, dir, onTick)
            PackKind.SINGLE_BOOK -> installSingleBook(def, onTick)
        }
    }

    private fun installZim(def: PackDef, dir: File, onTick: () -> Unit) {
        progress("Finding the latest version", 0, def.size, onTick)
        val prior = store[def.id]?.info
        // Keep resuming the same file; only look up a newer one when starting fresh.
        val src: Http.KiwixFile = prior?.optString("url")?.takeIf { it.isNotEmpty() }?.let {
            Http.KiwixFile(it, prior.optLong("expectedSize", def.size), prior.optString("sourceDate"), prior.optString("sha256").ifEmpty { null })
        } ?: try { Http.resolveKiwix(def.zimName, def.flavour) } catch (e: IOException) { null }
            ?: Http.KiwixFile(def.url, def.size, def.date, null)
        val file = File(dir, "${def.id}.zim")
        val info = JSONObject().put("file", file.name).put("url", src.url).put("expectedSize", src.size).put("sourceDate", src.date)
            .put("images", def.hasImages).put("category", def.category)
        src.sha256?.let { info.put("sha256", it) }
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        Http.download(src.url, file, { d, t -> progress("Downloading", d, if (t > 0) t else src.size, onTick) }, cancelled)
        if (src.size > 0 && file.length() != src.size) {
            file.delete(); throw IOException("download was incomplete (${humanBytes(file.length())} of ${humanBytes(src.size)}); it will start over")
        }
        if (src.sha256 != null) {
            val got = Verify.digest(file, "SHA-256", checking("Checking file against Kiwix's checksum", onTick), cancelled)
            if (!got.equals(src.sha256, true)) { file.delete(); throw IOException("downloaded file didn't match its checksum and was removed; it will download again") }
            info.put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", "SHA-256")
        } else {
            progress("Checking file", 0, 0, onTick)
            if (!checkZim(file, true)) { file.delete(); throw IOException("downloaded file is damaged") }
            info.put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", "ZIM checksum")
        }
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, info.put("installedAt", System.currentTimeMillis()))
    }

    private fun installWikipedia(def: PackDef, dir: File, onTick: () -> Unit) {
        val prior = store[def.id]?.info
        // Pin to one dated dump so a download that takes days (or spans a monthly update) never mixes two dumps.
        var info = prior?.takeIf { it.optString("dumpUrl").isNotEmpty() }?.let { JSONObject(it.toString()) }
        if (info == null) {
            progress("Finding the latest Wikipedia dump", 0, def.size, onTick)
            val wiki = def.json.optString("wiki").ifEmpty { if (def.id == "simplewiki") "simplewiki" else "enwiki" }
            val d = Http.resolveWikiDump(wiki)
            info = JSONObject().put("format", "multistream").put("dump", "dump.xml.bz2").put("titles", "titles.idx")
                .put("dumpUrl", d.dumpUrl).put("dumpSha1", d.dumpSha1).put("indexUrl", d.indexUrl).put("indexSha1", d.indexSha1)
                .put("sourceDate", d.date)
        }
        val titles = File(dir, "titles.idx")
        val dump = File(dir, "dump.xml.bz2")
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        if (!titles.exists()) {
            val idxFile = File(dir, "index.txt.bz2")
            if (!idxFile.exists()) {
                Http.download(info.getString("indexUrl"), idxFile, { d, t -> progress("Downloading title list", d, t, onTick) }, cancelled)
                val sha = Verify.digest(idxFile, "SHA-1", checking("Checking title list", onTick), cancelled)
                if (!sha.equals(info.optString("indexSha1"), true)) { idxFile.delete(); throw IOException("title list didn't match Wikimedia's checksum; it will download again") }
            }
            progress("Building search index", 0, 100, onTick)
            TitleIndex.build(idxFile, titles, File(dir, "work"), { f, n -> progress("Building search index · ${"%,d".format(n)} titles", (f * 100).toLong(), 100, onTick) }, cancelled)
            File(dir, "work").deleteRecursively()
            idxFile.delete()
            // The title index alone already lets you search titles.
            store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        }
        if (!dump.exists() || info.optLong("verifiedAt") == 0L) {
            if (!dump.exists()) Http.download(info.getString("dumpUrl"), dump, { d, t -> progress("Downloading articles", d, t, onTick) }, cancelled)
            val sha = Verify.digest(dump, "SHA-1", checking("Checking articles against Wikimedia's checksum", onTick), cancelled)
            if (!sha.equals(info.optString("dumpSha1"), true)) { dump.delete(); throw IOException("article file didn't match Wikimedia's checksum and was removed; tap download to get it again") }
            info.put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", "SHA-1")
        }
        info.put("installedAt", System.currentTimeMillis())
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, info)
    }

    private fun optimize(job: Job, onTick: () -> Unit) {
        val p = store[job.packId] ?: throw SkipPack("Not installed")
        if (p.info.optString("format") == "condensed") return
        val dump = p.file(p.info.optString("dump", "dump.xml.bz2"))
        val titles = p.file(p.info.optString("titles", "titles.idx"))
        val outData = p.file("articles.arkwiki")
        val outIdx = p.file("titles-condensed.idx")
        val free = storage.volumeOf(p.dir)?.free() ?: 0
        val need = (dump.length() * 0.62).toLong() - (if (outData.exists()) outData.length() else 0)
        if (need > free - 500_000_000L) throw SkipPack("Optimize needs about ${humanBytes(need)} free on the same storage")
        val total = dump.length()
        WikiCondenser(dump, titles, outData, outIdx).run({ pr ->
            progress("Optimizing · ${humanBytes(pr.outputBytes)} written", (pr.fraction * total).toLong(), total, onTick)
        }, cancelled)
        progress("Checking the optimized copy", 0, 0, onTick)
        if (!Verify.condensed(outData, sampleOffsets(outIdx))) { outData.delete(); outIdx.delete(); throw IOException("optimized copy failed its check; the original was kept") }
        release(p.id)
        // swap in the condensed files
        val info = JSONObject(p.info.toString()).put("format", "condensed").put("dump", outData.name).put("titles", "titles.idx")
            .put("optimizedAt", System.currentTimeMillis()).put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", "structure")
        titles.delete(); outIdx.renameTo(titles)
        dump.delete()
        store.write(p.id, p.kind, p.title, p.dir, PackState.INSTALLED, info)
    }

    private fun sampleOffsets(titleIdx: File, n: Int = 40): List<Long> = SortedIndex.open(titleIdx).use { idx ->
        val rnd = java.util.Random(1)
        (0 until n).mapNotNull {
            val block = idx.block(rnd.nextInt(idx.blockCount.coerceAtLeast(1)))
            block.getOrNull(rnd.nextInt(block.size.coerceAtLeast(1)))?.let { r -> ByteSource(r.value).let { s -> s.skipBytes(); s.varint() } }
        }.distinct()
    }

    private fun installMap(def: PackDef, dir: File, onTick: () -> Unit) {
        progress("Finding the map build", 0, def.size, onTick)
        // Resume from the same daily build while it still exists, so a multi-day download doesn't start over.
        val prior = store[def.id]?.info?.optString("source")?.takeIf { it.isNotEmpty() }
        val url = prior?.takeIf { Http.exists(it) } ?: Http.latestProtomaps()
        val file = File(dir, "map.pmtiles")
        val buildDate = Regex("(\\d{4})(\\d{2})(\\d{2})\\.pmtiles").find(url)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" } ?: today()
        val info = JSONObject().put("file", file.name).put("maxZoom", def.maxZoom).put("source", url).put("sourceDate", buildDate).put("def", def.json)
        def.group?.let { info.put("group", it) }
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        val ex = PmtilesExtractor(Http.client, url, def.areas, def.minZoom, def.maxZoom, file, Http.userAgent)
        ex.run({ p -> progress("Downloading map tiles · ${humanBytes(p.outputBytes)}", (p.fraction * def.size).toLong(), def.size, onTick) }, cancelled)
        progress("Checking map", 0, 0, onTick)
        if (!Verify.map(file)) { file.delete(); throw IOException("map file failed its check and was removed; tap download to try again") }
        info.put("installedAt", System.currentTimeMillis()).put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", "structure")
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, info)
        // A more detailed map of the same area replaces the less detailed ones.
        def.group?.let { g ->
            store.packs.value.values.filter { it.id != def.id && it.state == PackState.INSTALLED && groupOf(it) == g && maxZoomOf(it) <= def.maxZoom }
                .forEach { old -> release(old.id); store.delete(old.id); note(def.id, "Replaced ${old.title} (freed ${humanBytes(old.bytes)}).") }
        }
    }

    fun groupOf(p: InstalledPack): String? = p.info.optString("group").ifEmpty { null } ?: catalog[p.id]?.group
    fun maxZoomOf(p: InstalledPack): Int = p.info.optInt("maxZoom", catalog[p.id]?.maxZoom ?: 0)

    private fun bookFile(dir: File, item: BookItem, url: String): File {
        val ext = when {
            url.endsWith(".pdf") -> "pdf"; url.endsWith(".txt") -> "txt"; else -> "epub"
        }
        return File(File(dir, "books"), "${item.id}.$ext")
    }

    private fun downloadBook(dir: File, item: BookItem): File? {
        for (url in item.urls) {
            val f = bookFile(dir, item, url)
            if (f.exists() && Verify.book(f)) return f
            f.delete()
            try {
                Http.download(url, f, { _, _ -> }, cancelled)
                if (Verify.book(f)) return f
                f.delete()
            } catch (e: Cancelled) { throw e } catch (e: IOException) { /* try next format */ }
        }
        return null
    }

    private fun installBooks(def: PackDef, dir: File, onTick: () -> Unit) {
        val items = def.books
        val arr = JSONArray()
        var failed = 0
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, JSONObject().put("items", arr))
        items.forEachIndexed { i, item ->
            if (cancelled()) throw Cancelled()
            progress("Downloading ${i + 1} of ${items.size}: ${item.title.take(40)}", i.toLong(), items.size.toLong(), onTick)
            val f = downloadBook(dir, item)
            if (f == null) failed++
            else arr.put(JSONObject().put("src", item.src).put("t", item.title).put("a", item.author).put("g", item.genre).put("file", "books/" + f.name))
        }
        val info = JSONObject().put("items", arr).put("installedAt", System.currentTimeMillis()).put("failed", failed)
            .put("sourceDate", def.date).put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", "each book opened")
        store.write(def.id, def.kind, def.title, dir, if (arr.length() > 0) PackState.INSTALLED else PackState.PARTIAL, info)
        if (failed > 0) note(def.id, "$failed of ${items.size} books couldn't be downloaded; the rest are ready.")
    }

    private fun installSingleBook(def: PackDef, onTick: () -> Unit) {
        val item = def.books.first()
        val dir = packDirFor(MY_BOOKS)
        val existing = store[MY_BOOKS]
        val arr = existing?.info?.optJSONArray("items") ?: JSONArray()
        progress("Downloading ${item.title.take(50)}", 0, 1, onTick)
        val f = downloadBook(dir, item) ?: throw IOException("book unavailable")
        if ((0 until arr.length()).none { arr.getJSONObject(it).optString("src") == item.src })
            arr.put(JSONObject().put("src", item.src).put("t", item.title).put("a", item.author).put("g", item.genre).put("file", "books/" + f.name))
        store.write(MY_BOOKS, PackKind.BOOKS, "My Books", dir, PackState.INSTALLED, JSONObject().put("items", arr).put("installedAt", System.currentTimeMillis()))
    }

    private fun installGutenberg(def: PackDef, dir: File, onTick: () -> Unit) {
        val gz = File(dir, "pg_catalog.csv.gz")
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, JSONObject())
        Http.download(def.url, gz, { d, t -> progress("Downloading catalog", d, t, onTick) }, cancelled)
        val db = File(dir, "catalog.db")
        buildGutenberg(gz, db) { f -> progress("Indexing books", (f * 100).toLong(), 100, onTick) }
        gz.delete()
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, JSONObject().put("db", db.name).put("installedAt", System.currentTimeMillis())
            .put("sourceDate", today()))
    }

    // ------------------------------------------------------------- verify

    /** Re-checks a downloaded pack without the internet and records the result. */
    private fun verify(id: String, onTick: () -> Unit) {
        val p = store[id] ?: throw SkipPack("Not on this phone")
        val info = JSONObject(p.info.toString())
        val ok: Boolean
        val how: String
        when (p.kind) {
            PackKind.ZIM -> {
                val f = p.file(info.optString("file"))
                val sha = info.optString("sha256")
                if (sha.isNotEmpty()) {
                    how = "SHA-256 checksum"
                    ok = f.exists() && Verify.digest(f, "SHA-256", checking("Checking ${p.title}", onTick), cancelled).equals(sha, true)
                } else {
                    how = "ZIM's built-in checksum"; progress("Checking ${p.title}", 0, 0, onTick)
                    ok = f.exists() && checkZim(f, true)
                }
            }
            PackKind.WIKIPEDIA -> {
                val dump = p.file(info.optString("dump", "dump.xml.bz2"))
                val titles = p.file(info.optString("titles", "titles.idx"))
                if (info.optString("format") == "condensed") {
                    how = "readable sample of articles"; progress("Checking ${p.title}", 0, 0, onTick)
                    ok = titles.exists() && Verify.condensed(dump, sampleOffsets(titles, 200))
                } else if (info.optString("dumpSha1").isNotEmpty()) {
                    how = "Wikimedia's SHA-1 checksum"
                    ok = dump.exists() && Verify.digest(dump, "SHA-1", checking("Checking ${p.title}", onTick), cancelled).equals(info.optString("dumpSha1"), true)
                } else {
                    how = "file present"; ok = dump.exists() && titles.exists()
                }
            }
            PackKind.MAP, PackKind.CUSTOM_MAP -> {
                how = "map structure"; progress("Checking ${p.title}", 0, 0, onTick)
                ok = Verify.map(p.file(info.optString("file", "map.pmtiles")))
            }
            PackKind.BOOKS, PackKind.SINGLE_BOOK -> {
                how = "each book opened"
                val arr = info.optJSONArray("items") ?: JSONArray()
                var bad = 0
                for (i in 0 until arr.length()) {
                    progress("Checking books", i.toLong(), arr.length().toLong(), onTick)
                    if (!Verify.book(File(p.dir, arr.getJSONObject(i).optString("file")))) bad++
                }
                ok = bad == 0
            }
            PackKind.GUTENBERG -> { how = "catalog present"; ok = p.file(info.optString("db", "catalog.db")).exists() }
        }
        info.put("verifiedAt", System.currentTimeMillis()).put("verifiedBy", how).put("damaged", !ok)
        store.write(p.id, p.kind, p.title, p.dir, p.state, info)
        note(id, if (ok) "Verified: ${p.title} is intact ($how)." else "Damaged: ${p.title} failed its check ($how). Delete it and download again while you have internet.")
    }

    // ------------------------------------------------------------- move between phone storage and SD card

    private fun movePack(id: String, destRoot: String, onTick: () -> Unit) {
        val p = store[id] ?: throw SkipPack("Not on this phone")
        val dest = storage.volumes().firstOrNull { it.root.path == destRoot } ?: throw SkipPack("That storage isn't available right now")
        if (p.dir.path.startsWith(dest.root.path)) return
        val size = storage.dirSize(p.dir)
        if (size > dest.free() - storage.reserve) throw SkipPack("Not enough room on ${dest.label}: needs ${humanBytes(size)}")
        release(id)
        val target = File(dest.packsDir, id)
        target.deleteRecursively(); target.mkdirs()
        File(target, ".moving").writeText("from ${p.dir.path}")
        var copied = 0L
        speed.reset()
        val buf = ByteArray(1 shl 20)
        p.dir.walkTopDown().filter { it.isFile && it.name != "pack.json" }.forEach { src ->
            val rel = src.relativeTo(p.dir).path
            val out = File(target, rel); out.parentFile?.mkdirs()
            src.inputStream().use { i -> out.outputStream().use { o ->
                while (true) {
                    if (cancelled()) { target.deleteRecursively(); throw Cancelled() }
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); copied += n
                    progress("Moving to ${dest.label}", copied, size, onTick)
                }
            } }
            if (out.length() != src.length()) { target.deleteRecursively(); throw IOException("copy of ${src.name} came out the wrong size; nothing was moved") }
        }
        File(target, "pack.json").writeText(File(p.dir, "pack.json").readText())
        File(target, ".moving").delete()
        p.dir.deleteRecursively()
        store.refresh()
        note(id, "Moved ${p.title} to ${dest.label}.")
    }

    companion object {
        const val MY_BOOKS = "my-books"
    }
}

class SpeedMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    fun reset() = samples.clear()
    fun add(bytes: Long) {
        val now = System.currentTimeMillis()
        samples.addLast(now to bytes)
        while (samples.size > 2 && now - samples.first().first > 8000) samples.removeFirst()
    }
    fun rate(): Long {
        if (samples.size < 2) return 0
        val (t0, b0) = samples.first(); val (t1, b1) = samples.last()
        return if (t1 > t0 && b1 >= b0) (b1 - b0) * 1000 / (t1 - t0) else 0
    }
}
