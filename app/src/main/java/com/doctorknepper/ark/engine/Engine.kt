package com.doctorknepper.ark.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.doctorknepper.ark.core.Cancelled
import com.doctorknepper.ark.core.PmtilesExtractor
import com.doctorknepper.ark.core.TitleIndex
import com.doctorknepper.ark.core.WikiCondenser
import com.doctorknepper.ark.core.humanBytes
import com.doctorknepper.ark.data.BookItem
import com.doctorknepper.ark.data.Catalog
import com.doctorknepper.ark.data.PackDef
import com.doctorknepper.ark.data.PackKind
import com.doctorknepper.ark.data.PackState
import com.doctorknepper.ark.data.PackStore
import com.doctorknepper.ark.data.Settings
import com.doctorknepper.ark.data.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One unit of queued work. [def] is stored for packs that aren't in the catalog (custom maps, single books). */
data class Job(val packId: String, val defcon: Boolean, val def: PackDef? = null, val action: String = "install")

data class EngineState(
    val running: Boolean = false,
    val current: String? = null,
    val currentTitle: String = "",
    val step: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val bytesPerSec: Long = 0,
    val queue: List<Job> = emptyList(),
    val notes: Map<String, String> = emptyMap(),   // per-pack messages: skipped / failed / waiting
    val defconActive: Boolean = false,
    val waitingForWifi: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Runs the download queue one pack at a time, in order. It is driven by [DownloadService] so it keeps
 * working with the screen off, and it persists the queue so work resumes after a restart.
 */
class Engine(
    private val context: Context,
    private val catalog: Catalog,
    private val store: PackStore,
    private val storage: Storage,
    private val settings: Settings,
    private val verifyZim: (File) -> Boolean,
    private val buildGutenberg: (File, File, (Double) -> Unit) -> Unit,
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
        queueFile.writeText(a.toString())
    }

    private fun update(f: (EngineState) -> EngineState) { _state.value = f(_state.value) }

    fun defOf(job: Job): PackDef? = job.def ?: catalog[job.packId]

    fun enqueue(ids: List<String>, defcon: Boolean = false) {
        val existing = _state.value.queue.map { it.packId }.toSet()
        val add = ids.filter { it !in existing && !store.isInstalled(it) && catalog[it] != null }.map { Job(it, defcon) }
        val q = if (defcon) add + _state.value.queue.filter { it.packId !in ids } else _state.value.queue + add
        saveQueue(q)
        update { it.copy(queue = q, defconActive = it.defconActive || defcon, notes = it.notes - ids.toSet()) }
    }

    fun enqueueCustom(def: PackDef) {
        val q = _state.value.queue.filter { it.packId != def.id } + Job(def.id, false, def)
        saveQueue(q); update { it.copy(queue = q) }
    }

    fun enqueueOptimize(id: String) {
        val q = _state.value.queue + Job(id, false, null, "optimize")
        saveQueue(q); update { it.copy(queue = q) }
    }

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
                update { it.copy(current = job.packId, currentTitle = def.title, step = "Starting", done = 0, total = 0, bytesPerSec = 0) }
                val waitReason = networkProblem(def, job)
                if (waitReason != null) {
                    // move this job behind smaller ones that can run on mobile data, or wait
                    val q = _state.value.queue
                    val other = q.firstOrNull { o -> defOf(o)?.let { networkProblem(it, o) == null } == true }
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
                    when (job.action) {
                        "optimize" -> optimize(job, def, onTick)
                        else -> install(job, def, onTick)
                    }
                    note(job.packId, null)
                    dropFromQueue(job)
                } catch (e: Cancelled) {
                    if (stopAll) break
                    dropFromQueue(job)
                } catch (e: SkipPack) {
                    note(job.packId, e.message)
                    dropFromQueue(job)
                } catch (e: Exception) {
                    note(job.packId, "Failed: " + (e.message ?: e.javaClass.simpleName) + ". Tap download to resume.")
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
        val vol = storage.current()
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

    private val cancelled = { cancelCurrent || stopAll }

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
        val (url, size) = try { Http.resolveKiwix(def.zimName, def.flavour) ?: (def.url to def.size) } catch (e: IOException) { def.url to def.size }
        val file = File(dir, "${def.id}.zim")
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, JSONObject().put("file", file.name).put("url", url))
        Http.download(url, file, { d, t -> progress("Downloading", d, if (t > 0) t else size, onTick) }, cancelled)
        progress("Checking file", size, size, onTick)
        if (!verifyZim(file)) { file.delete(); throw IOException("downloaded file is damaged") }
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, JSONObject().put("file", file.name).put("url", url)
            .put("installedAt", System.currentTimeMillis()).put("images", def.hasImages).put("category", def.category))
    }

    private fun installWikipedia(def: PackDef, dir: File, onTick: () -> Unit) {
        val info = JSONObject().put("format", "multistream").put("dump", "dump.xml.bz2").put("titles", "titles.idx")
        val titles = File(dir, "titles.idx")
        val dump = File(dir, "dump.xml.bz2")
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        if (!titles.exists()) {
            val idxFile = File(dir, "index.txt.bz2")
            if (!idxFile.exists()) Http.download(def.json.getString("indexUrl"), idxFile, { d, t -> progress("Downloading title list", d, t, onTick) }, cancelled)
            progress("Building search index", 0, 100, onTick)
            TitleIndex.build(idxFile, titles, File(dir, "work"), { f, n -> progress("Building search index · ${"%,d".format(n)} titles", (f * 100).toLong(), 100, onTick) }, cancelled)
            File(dir, "work").deleteRecursively()
            idxFile.delete()
            // The title index alone already lets you search; mark that it exists.
            store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        }
        if (!dump.exists()) {
            Http.download(def.json.getString("dumpUrl"), dump, { d, t -> progress("Downloading articles", d, t, onTick) }, cancelled)
        }
        info.put("installedAt", System.currentTimeMillis())
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, info)
    }

    private fun optimize(job: Job, def: PackDef, onTick: () -> Unit) {
        val p = store[job.packId] ?: throw SkipPack("Not installed")
        if (p.info.optString("format") == "condensed") return
        val dump = p.file(p.info.optString("dump", "dump.xml.bz2"))
        val titles = p.file(p.info.optString("titles", "titles.idx"))
        val outData = p.file("articles.arkwiki")
        val outIdx = p.file("titles-condensed.idx")
        val free = storage.volumes().firstOrNull { p.dir.path.startsWith(it.root.path) }?.free() ?: 0
        val need = (dump.length() * 0.62).toLong() - (if (outData.exists()) outData.length() else 0)
        if (need > free - 500_000_000L) throw SkipPack("Optimize needs about ${humanBytes(need)} free on the same storage")
        val total = dump.length()
        WikiCondenser(dump, titles, outData, outIdx).run({ pr ->
            progress("Optimizing · ${humanBytes(pr.outputBytes)} written", (pr.fraction * total).toLong(), total, onTick)
        }, cancelled)
        // swap in the condensed files
        val info = JSONObject(p.info.toString()).put("format", "condensed").put("dump", outData.name).put("titles", "titles.idx")
            .put("optimizedAt", System.currentTimeMillis())
        titles.delete(); outIdx.renameTo(titles)
        dump.delete()
        store.write(p.id, p.kind, p.title, p.dir, PackState.INSTALLED, info)
    }

    private fun installMap(def: PackDef, dir: File, onTick: () -> Unit) {
        progress("Finding the latest map build", 0, def.size, onTick)
        val url = Http.latestProtomaps()
        val file = File(dir, "map.pmtiles")
        val info = JSONObject().put("file", file.name).put("maxZoom", def.maxZoom).put("source", url).put("def", def.json)
        store.write(def.id, def.kind, def.title, dir, PackState.PARTIAL, info)
        val ex = PmtilesExtractor(Http.client, url, def.areas, def.minZoom, def.maxZoom, file, Http.userAgent)
        ex.run({ p -> progress("Downloading map tiles · ${humanBytes(p.outputBytes)}", (p.fraction * def.size).toLong(), def.size, onTick) }, cancelled)
        info.put("installedAt", System.currentTimeMillis())
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, info)
    }

    private fun bookFile(dir: File, item: BookItem, url: String): File {
        val ext = when {
            url.endsWith(".pdf") -> "pdf"; url.endsWith(".txt") -> "txt"; else -> "epub"
        }
        return File(File(dir, "books"), "${item.id}.$ext")
    }

    private fun downloadBook(dir: File, item: BookItem): File? {
        for (url in item.urls) {
            val f = bookFile(dir, item, url)
            if (f.exists() && f.length() > 0) return f
            try {
                Http.download(url, f, { _, _ -> }, cancelled)
                if (f.length() > 200) return f
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
        store.write(def.id, def.kind, def.title, dir, PackState.INSTALLED, JSONObject().put("db", db.name).put("installedAt", System.currentTimeMillis()))
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
