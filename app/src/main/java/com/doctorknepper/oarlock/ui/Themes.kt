package com.doctorknepper.oarlock.ui

import androidx.compose.ui.graphics.Color

data class ArkTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Long,
    val surface: Long,
    val surface2: Long,
    val text: Long,
    val muted: Long,
    val accent: Long,
    val accent2: Long,
    val line: Long,
    val danger: Long,
    val onAccent: Long,
    val note: String = "",
) {
    fun c(v: Long) = Color(v)
    fun css(v: Long) = "#%06x".format(v and 0xFFFFFF)

    /** CSS custom properties used by every web page Ark renders. */
    fun cssVars(fontScale: Float) = """:root{--bg:${css(bg)};--surface:${css(surface)};--surface2:${css(surface2)};--text:${css(text)};
        |--muted:${css(muted)};--accent:${css(accent)};--accent2:${css(accent2)};--line:${css(line)};--link:${css(accent)};
        |--danger:${css(danger)};--on-accent:${css(onAccent)};--font-scale:$fontScale;color-scheme:${if (dark) "dark" else "light"};}""".trimMargin()

    companion object {
        val all = listOf(
            ArkTheme("amber", "OLED Amber", true, 0xFF000000, 0xFF0E0B06, 0xFF1A140A, 0xFFEFE6D6, 0xFFA0937C, 0xFFFFB000, 0xFFFFCF66, 0xFF2B2416, 0xFFFF4D3A, 0xFF000000,
                "Pure black saves battery on OLED screens."),
            ArkTheme("red", "Night Vision Red", true, 0xFF000000, 0xFF0F0303, 0xFF1A0606, 0xFFFF8A80, 0xFFB3554D, 0xFFFF3B30, 0xFFFF6B5E, 0xFF2E0B0B, 0xFFFF3B30, 0xFF000000,
                "Red light preserves your night vision in the dark."),
            ArkTheme("green", "Terminal Green", true, 0xFF000000, 0xFF050D07, 0xFF0A170D, 0xFFC8F5D2, 0xFF6F9E7A, 0xFF39FF88, 0xFF8CFFB5, 0xFF12301B, 0xFFFF5252, 0xFF000000),
            ArkTheme("blue", "Arctic Blue", true, 0xFF000000, 0xFF060A10, 0xFF0C131D, 0xFFDDE9F5, 0xFF7F92A8, 0xFF4FB3FF, 0xFF9BD3FF, 0xFF16222F, 0xFFFF5A5A, 0xFF000000),
            ArkTheme("graphite", "OLED Graphite", true, 0xFF000000, 0xFF0D0D0D, 0xFF171717, 0xFFE8E8E8, 0xFF8C8C8C, 0xFFF2F2F2, 0xFFBDBDBD, 0xFF262626, 0xFFFF5A5A, 0xFF000000),
            ArkTheme("dusk", "Dusk Amber", true, 0xFF17140F, 0xFF211D16, 0xFF2B251C, 0xFFF0E7D6, 0xFFA89A82, 0xFFFFB84D, 0xFFFFD699, 0xFF3A3226, 0xFFFF6B57, 0xFF17140F,
                "Softer dark gray instead of pure black."),
            ArkTheme("paper", "Paper", false, 0xFFF4ECD8, 0xFFEBE1C8, 0xFFE2D6B9, 0xFF2B2418, 0xFF6E6250, 0xFF9A5B00, 0xFF7A4700, 0xFFD5C8A8, 0xFFB3261E, 0xFFFFFFFF,
                "Warm sepia for comfortable daytime reading."),
            ArkTheme("daylight", "Daylight", false, 0xFFFFFFFF, 0xFFF4F4F4, 0xFFE9E9E9, 0xFF000000, 0xFF555555, 0xFFB35C00, 0xFF8A4600, 0xFFD6D6D6, 0xFFC62828, 0xFFFFFFFF,
                "High contrast for bright sunlight."),
        )

        fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
    }
}
