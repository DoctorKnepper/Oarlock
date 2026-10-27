package com.doctorknepper.oarlock.engine

import com.doctorknepper.oarlock.BuildConfig
import com.doctorknepper.oarlock.core.Cancelled
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object Http {
    val userAgent = "OarlockOfflineLibrary/${BuildConfig.VERSION_NAME} (Android; offline reference app)"

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
        // The validator (ETag or Last-Modified) of the file being resumed. With If-Range the server only sends the
        // remaining bytes if the file is unchanged; otherwise it sends the whole new file and we start over, so two
        // different versions are never spliced together.
        val vFile = File(part.path + ".v")
        val have = if (part.exists()) part.length() else 0L
        val validator = if (have > 0 && vFile.exists()) vFile.readText() else null
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        if (have > 0) {
            b.header("Range", "bytes=$have-")
            // If-Range only for single-server sources: Kiwix spreads files over mirrors whose validators differ for the
            // same file, which would restart every resume. All Oarlock URLs name a fixed version and are checksummed anyway.
            if (!url.contains("download.kiwix.org")) validator?.let { b.header("If-Range", it) }
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 416 && have > 0) { // already complete
                vFile.delete(); part.renameTo(target); return target
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} downloading ${url.substringAfterLast('/')}")
            val resumed = r.code == 206
            val body = r.body ?: throw IOException("empty response")
            val len = body.contentLength()
            val total = if (len < 0) -1 else if (resumed) have + len else len
            (r.header("ETag")?.takeIf { !it.startsWith("W/") } ?: r.header("Last-Modified"))?.let { if (!resumed) vFile.writeText(it) }
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
        vFile.delete()
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("could not save ${target.name}")
        return target
    }

    data class KiwixFile(val url: String, val size: Long, val date: String, val sha256: String?)

    /** Latest download for a Kiwix book name + flavour from the Kiwix OPDS catalog, with its published checksum. */
    fun resolveKiwix(name: String, flavour: String): KiwixFile? {
        val xml = String(get("https://library.kiwix.org/catalog/v2/entries?name=$name&lang=eng&count=50"))
        var best: Triple<String, String, Long>? = null
        for (entry in xml.split("<entry>").drop(1)) {
            val n = Regex("<name>(.*?)</name>").find(entry)?.groupValues?.get(1) ?: continue
            if (n != name) continue
            val fl = Regex("<flavour>(.*?)</flavour>").find(entry)?.groupValues?.get(1) ?: ""
            if (fl != flavour) continue
            val updated = Regex("<updated>(.*?)</updated>").find(entry)?.groupValues?.get(1) ?: ""
            val link = Regex("<link rel=\"http://opds-spec.org/acquisition/open-access\"[^>]*href=\"([^\"]+)\"[^>]*length=\"(\\d+)\"").find(entry) ?: continue
            if (best == null || updated > best.first) best = Triple(updated, link.groupValues[1], link.groupValues[2].toLong())
        }
        val (updated, href, len) = best ?: return null
        val meta4 = if (href.endsWith(".meta4")) runCatching { String(get(href)) }.getOrNull() else null
        val sha = meta4?.let { Regex("<hash type=\"sha-256\">([0-9a-f]{64})</hash>").find(it)?.groupValues?.get(1) }
        val size = meta4?.let { Regex("<size>(\\d+)</size>").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: len
        return KiwixFile(href.removeSuffix(".meta4"), size, updated.take(10), sha)
    }

    data class WikiDump(val date: String, val dumpUrl: String, val dumpSha1: String, val indexUrl: String, val indexSha1: String)

    /**
     * The newest completed Wikimedia dump for [wiki] (e.g. "enwiki"), pinned to its dated folder so a download that
     * spans months never mixes two dumps, with the SHA-1 checksums Wikimedia publishes.
     */
    fun resolveWikiDump(wiki: String): WikiDump {
        val sums = String(get("https://dumps.wikimedia.org/$wiki/latest/$wiki-latest-sha1sums.txt"))
        fun find(suffix: String): Pair<String, String> {
            val line = sums.lines().firstOrNull { it.trim().endsWith(suffix) && Regex("$wiki-\\d{8}-").containsMatchIn(it) }
                ?: throw IOException("Wikimedia checksum list has no $suffix")
            val (sha, name) = line.trim().split(Regex("\\s+"), limit = 2)
            return sha to name
        }
        val (dSha, dName) = find("-pages-articles-multistream.xml.bz2")
        val (iSha, iName) = find("-pages-articles-multistream-index.txt.bz2")
        val date = Regex("\\d{8}").find(dName)!!.value
        val base = "https://dumps.wikimedia.org/$wiki/$date/"
        return WikiDump("${date.take(4)}-${date.substring(4, 6)}-${date.substring(6)}", base + dName, dSha, base + iName, iSha)
    }

    /** True if [url] still exists (used to keep resuming from the same map build while it's available). */
    fun exists(url: String): Boolean = try { head(url); true } catch (e: IOException) { false }

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
