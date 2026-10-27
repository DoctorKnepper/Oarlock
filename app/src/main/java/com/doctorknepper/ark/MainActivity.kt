package com.doctorknepper.ark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.doctorknepper.ark.ui.ArkMaterial
import com.doctorknepper.ark.ui.ArkTheme
import com.doctorknepper.ark.ui.BooksScreen
import com.doctorknepper.ark.ui.CatalogScreen
import com.doctorknepper.ark.ui.DefconScreen
import com.doctorknepper.ark.ui.DownloadsScreen
import com.doctorknepper.ark.ui.FieldGuideScreen
import com.doctorknepper.ark.ui.HomeScreen
import com.doctorknepper.ark.ui.LibraryScreen
import com.doctorknepper.ark.ui.MapScreen
import com.doctorknepper.ark.ui.Nav
import com.doctorknepper.ark.ui.PdfScreen
import com.doctorknepper.ark.ui.ReaderScreen
import com.doctorknepper.ark.ui.SearchScreen
import com.doctorknepper.ark.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    // Lives as long as the app process, so the screen you were on survives the activity being recreated.
    companion object { val nav = Nav() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBars(Ark.theme)
        handleIntent(intent)
        askForNotifications()
        setContent {
            val themeId by Ark.settings.theme.collectAsState()
            val keepAwake by Ark.settings.keepAwake.collectAsState()
            val theme = remember(themeId) { ArkTheme.byId(themeId) }
            LaunchedEffect(theme) { applyBars(theme) }
            LaunchedEffect(keepAwake) {
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ArkMaterial(theme) {
                Box(Modifier.fillMaxSize().background(theme.c(theme.bg)).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    Router(nav)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra("open")) {
            "downloads" -> nav.go("downloads")
        }
    }

    private fun applyBars(t: ArkTheme) {
        val bg = Color.argb(255, ((t.bg shr 16) and 0xFF).toInt(), ((t.bg shr 8) and 0xFF).toInt(), (t.bg and 0xFF).toInt())
        val style = if (t.dark) SystemBarStyle.dark(bg) else SystemBarStyle.light(bg, bg)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (Ark.settings.onboarded) return
        Ark.settings.onboarded = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}

@Composable
fun Router(nav: Nav) {
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val route = nav.current
    // Each back-stack entry keeps its own saved UI state (scroll position, reader page).
    holder.SaveableStateProvider("${nav.stack.size}:$route") {
        when {
            route == "home" -> HomeScreen(nav)
            route == "search" -> SearchScreen(nav, "")
            route.startsWith("search?q=") -> SearchScreen(nav, route.removePrefix("search?q="))
            route.startsWith("catalog/") -> CatalogScreen(nav, route.removePrefix("catalog/").ifEmpty { null })
            route == "downloads" -> DownloadsScreen(nav)
            route == "defcon" -> DefconScreen(nav)
            route == "library" -> LibraryScreen(nav)
            route == "books" -> BooksScreen(nav)
            route == "settings" -> SettingsScreen(nav)
            route == "field" -> FieldGuideScreen(nav)
            route == "map" -> MapScreen(nav, "")
            route.startsWith("map?") -> MapScreen(nav, route.removePrefix("map?"))
            route.startsWith("read:") -> ReaderScreen(nav, route.removePrefix("read:"))
            route.startsWith("pdf:") -> {
                val (path, title) = route.removePrefix("pdf:").split('|', limit = 2).let { it[0] to (it.getOrNull(1) ?: "Book") }
                PdfScreen(nav, path, title)
            }
            else -> HomeScreen(nav)
        }
    }
}
