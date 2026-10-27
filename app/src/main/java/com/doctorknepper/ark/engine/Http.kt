package com.doctorknepper.ark.engine

import com.doctorknepper.ark.BuildConfig
import com.doctorknepper.ark.core.Cancelled
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object Http {
    val userAgent = "ArkOfflineLibrary/${BuildConfig.VERSION_NAME} (Android; offline reference app)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun get(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body!!.bytes()
        }
    }

    fun head(url: String): Long {
        val req = Request.Builder().url(url).head().header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.header("Content-Length")?.toLongOrNull() ?: -1
        }
    }

    /**
     * Downloads [url] to [target], resuming from "<target>.part" when the server supports ranges.
     * [onProgress] receives (bytesSoFar, totalBytes or -1).
     */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File {
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            try {
                return attemptDownload(url, target, part, onProgress, isCancelled)
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 8) throw e
                // back off, keep the partial file for resuming
                val wait = minOf(60_000L, 2_000L * attempt * attempt)
                var waited = 0L
                while (waited < wait) { if (isCancelled()) throw Cancelled(); Thread.sleep(500); waited += 500 }
            }
        }
    }

    private fun attemptDownload(url: String, target: File, part: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean): File {
        val have = if (part.exists()) part.length() else 0L
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        if (have > 0) b.header("Range", "bytes=$have-")
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 416 && have > 0) { // already complete
                part.renameTo(target); return target
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} downloading ${url.substringAfterLast('/')}")
            val resumed = r.code == 206
            val body = r.body ?: throw IOException("empty response")
            val len = body.contentLength()
            val total = if (len < 0) -1 else if (resumed) have + len else len
            RandomAccessFile(part, "rw").use { out ->
                var pos = if (resumed) have else 0L
                out.setLength(pos); out.seek(pos)
                val buf = ByteArray(256 * 1024)
                val inp = body.byteStream()
                var lastReport = 0L
                while (true) {
                    if (isCancelled()) throw Cancelled()
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); pos += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 400) { onProgress(pos, total); lastReport = now }
                }
                if (total > 0 && pos < total) throw IOException("connection closed early")
                onProgress(pos, total)
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("could not save ${target.name}")
        return target
    }

    /** Latest download URL for a Kiwix book name + flavour from the Kiwix OPDS catalog. */
    fun resolveKiwix(name: String, flavour: String): Pair<String, Long>? {
        val xml = String(get("https://library.kiwix.org/catalog/v2/entries?name=$name&lang=eng&count=50"))
        var best: Triple<String, String, Long>? = null
        for (entry in xml.split("<entry>").drop(1)) {
            val n = Regex("<name>(.*?)</name>").find(entry)?.groupValues?.get(1) ?: continue
            if (n != name) continue
            val fl = Regex("<flavour>(.*?)</flavour>").find(entry)?.groupValues?.get(1) ?: ""
            if (fl != flavour) continue
            val updated = Regex("<updated>(.*?)</updated>").find(entry)?.groupValues?.get(1) ?: ""
            val link = Regex("<link rel=\"http://opds-spec.org/acquisition/open-access\"[^>]*href=\"([^\"]+)\"[^>]*length=\"(\\d+)\"").find(entry) ?: continue
            val href = link.groupValues[1].removeSuffix(".meta4")
            if (best == null || updated > best.first) best = Triple(updated, href, link.groupValues[2].toLong())
        }
        return best?.let { it.second to it.third }
    }

    /** URL of the newest Protomaps daily planet build. */
    fun latestProtomaps(): String {
        val arr = JSONArray(String(get("https://build-metadata.protomaps.dev/builds.json")))
        var key = ""
        for (i in 0 until arr.length()) {
            val k = arr.getJSONObject(i).getString("key")
            if (k.endsWith(".pmtiles") && k > key) key = k
        }
        require(key.isNotEmpty()) { "no map builds listed" }
        return "https://build.protomaps.com/$key"
    }
}
