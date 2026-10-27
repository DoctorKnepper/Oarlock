package com.doctorknepper.oarlock

import android.app.Application
import android.content.Context
import com.doctorknepper.oarlock.content.BookLibrary
import com.doctorknepper.oarlock.content.GutenbergCatalog
import com.doctorknepper.oarlock.content.MapLibrary
import com.doctorknepper.oarlock.content.WikiLibrary
import com.doctorknepper.oarlock.content.ZimLibrary
import com.doctorknepper.oarlock.data.Catalog
import com.doctorknepper.oarlock.data.PackStore
import com.doctorknepper.oarlock.data.Settings
import com.doctorknepper.oarlock.data.Storage
import com.doctorknepper.oarlock.engine.DownloadService
import com.doctorknepper.oarlock.engine.Engine
import com.doctorknepper.oarlock.ui.ArkTheme
import java.io.File

/** App-wide singletons. */
object Ark {
    lateinit var app: Context; private set
    lateinit var settings: Settings; private set
    lateinit var storage: Storage; private set
    lateinit var store: PackStore; private set
    lateinit var catalog: Catalog; private set
    lateinit var engine: Engine; private set
    lateinit var wiki: WikiLibrary; private set
    lateinit var zim: ZimLibrary; private set
    lateinit var books: BookLibrary; private set
    lateinit var maps: MapLibrary; private set
    lateinit var gutenberg: GutenbergCatalog; private set

    val imageCache: File get() = File(storage.current().root, "image-cache").apply { mkdirs() }

    val theme: ArkTheme get() = ArkTheme.byId(settings.theme.value)

    fun init(context: Context) {
        app = context.applicationContext
        settings = Settings(app)
        storage = Storage(app, settings)
        store = PackStore(storage)
        store.onRefresh = { packs -> trackRemovable(packs) }
        store.refresh()
        catalog = Catalog.load(app)
        engine = Engine(app, catalog, store, storage, settings, ZimLibrary::verify, { gz, db, p -> GutenbergCatalog.build(gz, db, p) }, { id -> release(id) })
        wiki = WikiLibrary(store)
        zim = ZimLibrary(store)
        books = BookLibrary(store)
        maps = MapLibrary(store)
        gutenberg = GutenbergCatalog(store)
        DownloadService.channels(app)
        // Clear leftovers from interrupted work (orphaned folders, abandoned Optimize copies) in the background.
        Thread { runCatching { engine.cleanup() } }.start()
    }

    /** Remembers packs stored on an SD card so they can be explained (not just vanish) when the card is removed. */
    private fun trackRemovable(packs: Map<String, com.doctorknepper.oarlock.data.InstalledPack>) {
        val cardIn = storage.volumes().any { it.removable }
        val now = packs.values.filter { storage.isRemovable(it.dir) }.associate { it.id to "${it.title}|${it.bytes}" }
        val keep = if (cardIn) emptyMap() else settings.removablePacks().filterKeys { it !in packs }
        settings.rememberRemovable(keep + now)
    }

    /** Packs that live on an SD card that isn't inserted right now: id to (title, bytes). */
    fun missingOnCard(): Map<String, Pair<String, Long>> {
        if (storage.volumes().any { it.removable }) return emptyMap()
        return settings.removablePacks().filterKeys { store[it] == null }.mapValues { (_, v) -> v.substringBefore('|') to (v.substringAfter('|').toLongOrNull() ?: 0) }
    }

    /** Closes open files of a pack before it is deleted or replaced. */
    fun release(id: String) {
        wiki.close(id); zim.close(id); maps.close(id)
    }

    fun deletePack(id: String) {
        engine.remove(id)
        release(id)
        store.delete(id)
    }
}

class ArkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Ark.init(this)
    }
}
