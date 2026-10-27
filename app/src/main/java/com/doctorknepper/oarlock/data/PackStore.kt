package com.doctorknepper.oarlock.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

enum class PackState { INSTALLED, PARTIAL }

/** A pack on disk. [info] holds kind-specific details (file names, formats, dates). */
data class InstalledPack(
    val id: String,
    val kind: PackKind,
    val title: String,
    val dir: File,
    val state: PackState,
    val bytes: Long,
    val info: JSONObject,
) {
    fun file(name: String) = File(dir, name)
    val installedAt get() = info.optLong("installedAt")
}

/** Tracks which packs are installed by reading each pack folder's pack.json. */
class PackStore(private val storage: Storage) {
    private val _packs = MutableStateFlow<Map<String, InstalledPack>>(emptyMap())
    val packs: StateFlow<Map<String, InstalledPack>> = _packs

    fun refresh() {
        val out = LinkedHashMap<String, InstalledPack>()
        for (v in storage.volumes()) {
            val dirs = v.packsDir.listFiles() ?: continue
            for (d in dirs) {
                val f = File(d, "pack.json")
                if (!f.exists()) continue
                try {
                    val o = JSONObject(f.readText())
                    val p = InstalledPack(
                        o.getString("id"), PackKind.valueOf(o.getString("kind")), o.optString("title"), d,
                        PackState.valueOf(o.optString("state", "PARTIAL")), o.optLong("bytes"), o.optJSONObject("info") ?: JSONObject()
                    )
                    out[p.id] = p
                } catch (e: Exception) { /* ignore unreadable pack folder */ }
            }
        }
        _packs.value = out
        onRefresh?.invoke(out)
    }

    /** Called after every refresh (used to remember which packs live on an SD card). */
    var onRefresh: ((Map<String, InstalledPack>) -> Unit)? = null

    operator fun get(id: String): InstalledPack? = _packs.value[id]

    fun isInstalled(id: String) = _packs.value[id]?.state == PackState.INSTALLED

    fun write(id: String, kind: PackKind, title: String, dir: File, state: PackState, info: JSONObject) {
        dir.mkdirs()
        val bytes = storage.dirSize(dir)
        val o = JSONObject().put("id", id).put("kind", kind.name).put("title", title).put("state", state.name)
            .put("bytes", bytes).put("info", info)
        val tmp = File(dir, "pack.json.tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(File(dir, "pack.json"))
        refresh()
    }

    fun delete(id: String) {
        _packs.value[id]?.dir?.deleteRecursively()
        // also clear partial folders on other volumes
        for (v in storage.volumes()) File(v.packsDir, id).takeIf { it.exists() }?.deleteRecursively()
        refresh()
    }

    fun totalBytes(): Long = _packs.value.values.sumOf { it.bytes }
}
