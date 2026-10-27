package com.doctorknepper.oarlock

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.data.PackState
import com.doctorknepper.oarlock.ui.ArkMaterial
import com.doctorknepper.oarlock.ui.ArkTheme
import com.doctorknepper.oarlock.ui.Nav
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders real Compose screens on the JVM so the design can be reviewed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("/home/claude/testdata/screens2").apply { mkdirs() }

    private fun seed() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vol = Ark.storage.current()
        // A 128 GB phone with 41 GB free, so storage displays look realistic.
        org.robolectric.shadows.ShadowStatFs.registerStats(vol.root, 31_250_000, 10_000_000, 10_000_000)
        fun pack(id: String, kind: PackKind, title: String, bytes: Int, info: JSONObject = JSONObject().put("installedAt", 1761500000000)) {
            val d = File(vol.packsDir, id); d.mkdirs()
            File(d, "data.bin").writeBytes(ByteArray(bytes))
            Ark.store.write(id, kind, title, d, PackState.INSTALLED, info)
        }
        pack("wikem", PackKind.ZIM, "WikEM Emergency Medicine", 40_000)
        pack("water", PackKind.ZIM, "Water Treatment Library", 20_000)
        pack("map-world", PackKind.MAP, "World Overview Map", 60_000)
        // a book collection with two "books"
        val bd = File(vol.packsDir, "books-sacred"); File(bd, "books").mkdirs()
        val items = JSONArray()
        listOf(Triple("pg-10", "The King James Version of the Bible", "Christianity"), Triple("pg-2800", "The Koran (Al-Qur'an)", "Islam"),
            Triple("pg-2388", "The Song Celestial; Or, Bhagavad-Gîtâ", "Hinduism"), Triple("pg-2017", "Dhammapada", "Buddhism")).forEach { (id, t, g) ->
            File(bd, "books/$id.epub").writeBytes(ByteArray(1000))
            items.put(JSONObject().put("src", id.replace('-', ':')).put("t", t).put("a", "").put("g", g).put("file", "books/$id.epub"))
        }
        Ark.store.write("books-sacred", PackKind.BOOKS, "Sacred & Spiritual Texts", bd, PackState.INSTALLED, JSONObject().put("items", items).put("installedAt", 1761500000000))
        Ark.engine.enqueue(listOf("wikimed", "zim-medicine", "map-na-12"))
        Ark.settings.setLastPage("Water purification", "https://ark.local/wiki/wikipedia/Water%20purification")
        Ark.settings.toggleSaved("Cardiopulmonary resuscitation", "https://ark.local/wiki/wikipedia/CPR")
    }

    private fun shoot(name: String, route: String, theme: String = "amber", prep: () -> Unit = {}) {
        seed()
        prep()
        val nav = Nav(route)
        compose.setContent {
            val t = ArkTheme.byId(theme)
            ArkMaterial(t) { Box(Modifier.fillMaxSize().background(t.c(t.bg))) { Router(nav) } }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot("home", "home")
    @Test fun homeRed() = shoot("home-red", "home", "red")
    @Test fun homePaper() = shoot("home-paper", "home", "paper")
    @Test fun catalog() = shoot("catalog", "catalog/Books")
    @Test fun catalogMaps() = shoot("catalog-maps", "catalog/Maps")
    @Test fun downloads() = shoot("downloads", "downloads")
    @Test fun defcon() = shoot("defcon", "defcon")
    @Test fun library() = shoot("library", "library")
    @Test fun books() = shoot("books", "books")
    @Test fun settings() = shoot("settings", "settings")
    @Test fun field() = shoot("field", "field")
    @Test fun search() = shoot("search", "search?q=water")
    @Test fun mapEmpty() = shoot("map-empty", "map") { Ark.store.delete("map-world") }
    @Test fun planner() = shoot("planner", "planner")
    @Test fun ice() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        com.doctorknepper.oarlock.ui.Ice.save(ctx, mapOf("name" to "Sam Rivera", "dob" to "March 3, 1985", "blood" to "O negative", "allergies" to "Penicillin, bee stings",
            "meds" to "Lisinopril 10 mg daily", "c1name" to "Alex Rivera (spouse)", "c1phone" to "+1 555 010 2233"))
        shoot("ice", "ice")
    }
    @Test fun fieldSpanish() { Ark.settings.setFieldLang("es"); shoot("field-es", "field") }
}
