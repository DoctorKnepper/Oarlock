package com.doctorknepper.oarlock.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.doctorknepper.oarlock.web.ArkWeb

/**
 * A WebView wired to Ark's offline content server. All https://ark.local/ requests are answered
 * locally; anything else is blocked inside the app and offered to the phone's browser when online.
 */
@SuppressLint("SetJavaScriptEnabled")
fun arkWebView(
    context: Context,
    bg: Int,
    onTitle: (String) -> Unit = {},
    onPageUrl: (String) -> Unit = {},
    onNavigate: (String) -> Boolean = { false },
): WebView = WebView(context).apply {
    setBackgroundColor(bg)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.textZoom = 100
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    isVerticalScrollBarEnabled = true
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) { if (!title.isNullOrBlank() && !title.startsWith("https://")) onTitle(title) }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (u.host == ArkWeb.HOST) return ArkWeb.handle(u)
            // Block every other network request so pages behave the same offline and online.
            return WebResourceResponse("text/plain", "utf-8", 403, "Offline", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (onNavigate(u.toString())) return true
            if (u.host == ArkWeb.HOST) return false
            if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto") {
                if (isOnline(context)) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else Toast.makeText(context, "That link is on the internet, which isn't available right now.", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) { url?.let(onPageUrl) }
    }
}

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun argb(v: Long): Int = AColor.argb(0xFF, ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())

fun Uri.isArk() = host == ArkWeb.HOST
