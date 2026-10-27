package com.doctorknepper.ark

import android.app.Application
import android.content.Context
import com.doctorknepper.ark.content.BookLibrary
import com.doctorknepper.ark.content.GutenbergCatalog
import com.doctorknepper.ark.content.MapLibrary
import com.doctorknepper.ark.content.WikiLibrary
import com.doctorknepper.ark.content.ZimLibrary
import com.doctorknepper.ark.data.Catalog
import com.doctorknepper.ark.data.PackStore
import com.doctorknepper.ark.data.Settings
import com.doctorknepper.ark.data.Storage
import com.doctorknepper.ark.engine.DownloadService
import com.doctorknepper.ark.engine.Engine
import com.doctorknepper.ark.ui.ArkTheme
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
        store = PackStore(storage).also { it.refresh() }
        catalog = Catalog.load(app)
        engine = Engine(app, catalog, store, storage, settings, ZimLibrary::verify) { gz, db, p -> GutenbergCatalog.build(gz, db, p) }
        wiki = WikiLibrary(store)
        zim = ZimLibrary(store)
        books = BookLibrary(store)
        maps = MapLibrary(store)
        gutenberg = GutenbergCatalog(store)
        DownloadService.channels(app)
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
