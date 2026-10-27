package com.doctorknepper.oarlock.content

import com.doctorknepper.oarlock.core.PmtilesReader
import com.doctorknepper.oarlock.data.PackKind
import com.doctorknepper.oarlock.data.PackState
import com.doctorknepper.oarlock.data.PackStore
import org.json.JSONArray
import org.json.JSONObject

/** Serves vector tiles from installed map packs. */
class MapLibrary(private val store: PackStore) {
    private val readers = HashMap<String, PmtilesReader>()

    fun mapPacks() = store.packs.value.values.filter { (it.kind == PackKind.MAP || it.kind == PackKind.CUSTOM_MAP) && it.state == PackState.INSTALLED }

    @Synchronized
    fun reader(id: String): PmtilesReader? {
        readers[id]?.let { return it }
        val p = store[id] ?: return null
        if (p.state != PackState.INSTALLED) return null
        return try { PmtilesReader(p.file(p.info.optString("file", "map.pmtiles"))).also { readers[id] = it } } catch (e: Exception) { null }
    }

    @Synchronized
    fun close(id: String) { readers.remove(id)?.close() }

    fun tile(id: String, z: Int, x: Long, y: Long): ByteArray? = reader(id)?.tile(z, x, y)

    /** Sources for the map page, least detailed first so detailed packs draw on top. */
    fun sourcesJson(): String {
        val arr = JSONArray()
        mapPacks().mapNotNull { p -> reader(p.id)?.let { p to it } }.sortedBy { it.second.header.maxZ * 1000 - areaOf(it.second) }
            .forEach { (p, r) ->
                val b = r.bounds
                arr.put(JSONObject().put("id", p.id).put("title", p.title).put("minzoom", r.header.minZ).put("maxzoom", r.header.maxZ)
                    .put("bounds", JSONArray().put(b.minLon).put(b.minLat).put(b.maxLon).put(b.maxLat)))
            }
        return arr.toString()
    }

    private fun areaOf(r: PmtilesReader): Int { val b = r.bounds; return ((b.maxLon - b.minLon) * (b.maxLat - b.minLat) / 100).toInt().coerceIn(0, 999) }
}
