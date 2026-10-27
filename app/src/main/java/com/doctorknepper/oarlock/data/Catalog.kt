package com.doctorknepper.oarlock.data

import android.content.Context
import com.doctorknepper.oarlock.core.Bbox
import org.json.JSONArray
import org.json.JSONObject

enum class PackKind { WIKIPEDIA, ZIM, MAP, BOOKS, GUTENBERG, CUSTOM_MAP, SINGLE_BOOK }

data class BookItem(val src: String, val title: String, val author: String, val genre: String, val lite: Boolean = false) {
    /** Stable file-safe id, e.g. "pg-2800" or "ia-zendavesta02darm". */
    val id: String get() = src.replace(':', '-').replace(Regex("[^A-Za-z0-9._-]"), "_")
    val urls: List<String>
        get() = if (src.startsWith("pg:")) {
            val n = src.substring(3)
            val withImages = "https://www.gutenberg.org/ebooks/$n.epub3.images"
            val textOnly = "https://www.gutenberg.org/ebooks/$n.epub.noimages"
            // "lite" books have very large illustrated editions, so the text-only EPUB comes first.
            (if (lite) listOf(textOnly, withImages) else listOf(withImages, textOnly)) + "https://www.gutenberg.org/cache/epub/$n/pg$n.txt"
        } else {
            val i = src.substring(3)
            listOf("https://archive.org/download/$i/$i.epub", "https://archive.org/download/$i/$i.pdf")
        }
}

data class PackDef(
    val id: String,
    val kind: PackKind,
    val title: String,
    val desc: String,
    val category: String,
    val size: Long,
    val json: JSONObject,
) {
    val zimName get() = json.optString("zimName")
    val flavour get() = json.optString("flavour")
    val url get() = json.optString("url")
    val hasImages get() = json.optBoolean("images")
    val group get() = json.optString("group").ifEmpty { null }
    val areas: List<Bbox> get() = json.optJSONArray("areas")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { Bbox(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) } } } ?: emptyList()
    val minZoom get() = json.optInt("minZoom", 0)
    val maxZoom get() = json.optInt("maxZoom", 14)
    val books: List<BookItem> get() = json.optJSONArray("items")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { BookItem(it.getString("src"), it.getString("t"), it.optString("a"), it.optString("g"), it.optBoolean("lite")) } } } ?: emptyList()
    /** Date of the content (Kiwix build, dump or map build), "" if unknown. */
    val date get() = json.optString("date")
    /** Extra temporary space a pack needs while installing (indexes, unpacking). */
    val extraSpace get() = json.optLong("extraSpace", 0)
}

/** DEFCON tiers: Core fits on nearly any phone; Full adds the large packs. Full always includes Core. */
class Catalog(val packs: List<PackDef>, val categories: List<String>, val defaultCore: List<String>, val defaultFullExtra: List<String>) {
    private val byId = packs.associateBy { it.id }
    operator fun get(id: String): PackDef? = byId[id]

    companion object {
        fun load(context: Context): Catalog {
            val root = JSONObject(context.assets.open("catalog.json").bufferedReader().readText())
            val arr = root.getJSONArray("packs")
            val packs = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PackDef(
                    o.getString("id"), PackKind.valueOf(o.getString("kind").uppercase()), o.getString("title"), o.optString("desc"),
                    o.optString("category"), o.optLong("size"), o
                )
            }
            val d = root.getJSONObject("defcon")
            return Catalog(packs, root.getJSONArray("categories").strings(), d.getJSONArray("core").strings(), d.getJSONArray("full").strings())
        }

        /** Definition for a map pack the user drew on the map ("my area"). */
        fun customMap(id: String, title: String, area: Bbox, maxZoom: Int, estimate: Long): PackDef {
            val o = JSONObject().put("id", id).put("kind", "custom_map").put("title", title)
                .put("areas", JSONArray().put(JSONArray().put(area.minLon).put(area.minLat).put(area.maxLon).put(area.maxLat)))
                .put("minZoom", 0).put("maxZoom", maxZoom).put("size", estimate)
            return PackDef(id, PackKind.CUSTOM_MAP, title, "Detailed map of an area you chose.", "Maps", estimate, o)
        }
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
