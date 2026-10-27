package com.doctorknepper.oarlock.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

data class Volume(val root: File, val label: String, val removable: Boolean) {
    val packsDir get() = File(root, "packs")
    fun free(): Long = try { StatFs(root.path).availableBytes } catch (e: Exception) { 0 }
    fun total(): Long = try { StatFs(root.path).totalBytes } catch (e: Exception) { 0 }
}

/** Where packs live: app-specific external storage on every mounted volume (phone storage and SD cards). */
class Storage(private val context: Context, private val settings: Settings) {

    fun volumes(): List<Volume> {
        val out = ArrayList<Volume>()
        val dirs = context.getExternalFilesDirs(null)
        dirs.filterNotNull().forEachIndexed { i, d ->
            val state = Environment.getExternalStorageState(d)
            if (state == Environment.MEDIA_MOUNTED) {
                val removable = try { Environment.isExternalStorageRemovable(d) } catch (e: Exception) { i > 0 }
                out.add(Volume(d, if (removable) "SD card" else "Phone storage", removable))
            }
        }
        if (out.isEmpty()) out.add(Volume(context.filesDir, "Phone storage", false))
        return out
    }

    /** The volume new downloads go to. */
    fun current(): Volume {
        val v = volumes()
        val chosen = settings.storagePath
        return v.firstOrNull { it.root.path == chosen } ?: v.first()
    }

    /** Space Ark keeps free so the phone stays usable. */
    val reserve: Long get() = 1_000_000_000L

    fun packDir(volume: Volume, id: String) = File(volume.packsDir, id)

    /** The volume a pack folder lives on. */
    fun volumeOf(dir: File): Volume? = volumes().firstOrNull { dir.path.startsWith(it.root.path) }

    fun isRemovable(dir: File) = volumeOf(dir)?.removable == true

    fun dirSize(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0)
}
