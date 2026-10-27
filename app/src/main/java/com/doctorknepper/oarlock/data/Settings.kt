package com.doctorknepper.oarlock.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ark", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(prefs.getString("theme", "amber")!!)
    val theme: StateFlow<String> = _theme
    fun setTheme(id: String) { prefs.edit().putString("theme", id).apply(); _theme.value = id }

    private val _fontScale = MutableStateFlow(prefs.getFloat("fontScale", 1f))
    val fontScale: StateFlow<Float> = _fontScale
    fun setFontScale(v: Float) { prefs.edit().putFloat("fontScale", v).apply(); _fontScale.value = v }

    var storagePath: String?
        get() = prefs.getString("storagePath", null)
        set(v) { prefs.edit().putString("storagePath", v).apply() }

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean("wifiOnlyLarge", true))
    val wifiOnlyLarge: StateFlow<Boolean> = _wifiOnly
    fun setWifiOnlyLarge(v: Boolean) { prefs.edit().putBoolean("wifiOnlyLarge", v).apply(); _wifiOnly.value = v }

    private val _onlineImages = MutableStateFlow(prefs.getBoolean("onlineImages", true))
    val onlineImages: StateFlow<Boolean> = _onlineImages
    fun setOnlineImages(v: Boolean) { prefs.edit().putBoolean("onlineImages", v).apply(); _onlineImages.value = v }

    private val _cacheImages = MutableStateFlow(prefs.getBoolean("cacheImages", true))
    val cacheImages: StateFlow<Boolean> = _cacheImages
    fun setCacheImages(v: Boolean) { prefs.edit().putBoolean("cacheImages", v).apply(); _cacheImages.value = v }

    private val _keepAwake = MutableStateFlow(prefs.getBoolean("keepAwake", false))
    val keepAwake: StateFlow<Boolean> = _keepAwake
    fun setKeepAwake(v: Boolean) { prefs.edit().putBoolean("keepAwake", v).apply(); _keepAwake.value = v }

    /** User's DEFCON Core list and the extra packs Full adds on top (null = catalog default). */
    private val _defconCore = MutableStateFlow(prefs.getString("defconCore", null)?.let { JSONArray(it).strings() })
    val defconCore: StateFlow<List<String>?> = _defconCore
    fun setDefconCore(ids: List<String>) { prefs.edit().putString("defconCore", JSONArray(ids).toString()).apply(); _defconCore.value = ids }

    private val _defconFull = MutableStateFlow(prefs.getString("defconFull", null)?.let { JSONArray(it).strings() })
    val defconFull: StateFlow<List<String>?> = _defconFull
    fun setDefconFull(ids: List<String>) { prefs.edit().putString("defconFull", JSONArray(ids).toString()).apply(); _defconFull.value = ids }

    fun resetDefcon() { prefs.edit().remove("defconCore").remove("defconFull").apply(); _defconCore.value = null; _defconFull.value = null }

    /** True after the user pauses downloads, so a reboot or relaunch doesn't restart them. */
    var pausedByUser: Boolean
        get() = prefs.getBoolean("pausedByUser", false)
        set(v) { prefs.edit().putBoolean("pausedByUser", v).apply() }

    private val _regionNudge = MutableStateFlow(prefs.getBoolean("regionNudgeDismissed", false))
    val regionNudgeDismissed: StateFlow<Boolean> = _regionNudge
    fun dismissRegionNudge() { prefs.edit().putBoolean("regionNudgeDismissed", true).apply(); _regionNudge.value = true }

    /** Packs last seen on a removable card: id -> "title|bytes", to explain packs that vanish when the card is out. */
    fun rememberRemovable(map: Map<String, String>) { prefs.edit().putString("removablePacks", org.json.JSONObject(map).toString()).apply() }
    fun removablePacks(): Map<String, String> = try {
        val o = org.json.JSONObject(prefs.getString("removablePacks", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    private val _fieldLang = MutableStateFlow(prefs.getString("fieldLang", "en")!!)
    val fieldLang: StateFlow<String> = _fieldLang
    fun setFieldLang(v: String) { prefs.edit().putString("fieldLang", v).apply(); _fieldLang.value = v }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }

    var lastArticle: String?
        get() = prefs.getString("lastArticle", null)
        set(v) { prefs.edit().putString("lastArticle", v).apply() }

    /** Last page opened in the reader, as "title|url", for "Continue reading". */
    private val _lastPage = MutableStateFlow(prefs.getString("lastPage", null))
    val lastPage: StateFlow<String?> = _lastPage
    fun setLastPage(title: String, url: String) {
        val v = "$title|$url"
        if (v == _lastPage.value) return
        prefs.edit().putString("lastPage", v).apply(); _lastPage.value = v
    }

    /** Saved pages, newest first: list of (title, url). */
    private val _saved = MutableStateFlow(loadSaved())
    val saved: StateFlow<List<Pair<String, String>>> = _saved

    private fun loadSaved(): List<Pair<String, String>> = try {
        val a = JSONArray(prefs.getString("saved", "[]"))
        (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
    } catch (e: Exception) { emptyList() }

    fun isSaved(url: String) = _saved.value.any { it.second == url }

    fun toggleSaved(title: String, url: String) {
        val list = if (isSaved(url)) _saved.value.filter { it.second != url } else listOf(title to url) + _saved.value
        val a = JSONArray(); list.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString("saved", a.toString()).apply(); _saved.value = list
    }
}
