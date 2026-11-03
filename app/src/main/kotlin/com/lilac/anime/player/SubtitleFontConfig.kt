package com.lilac.anime.player

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/** Keep fontconfig's expensive system-font scan across libass track reloads. */
internal object SubtitleFontConfig {
    @Synchronized
    fun prepare(context: Context) {
        try {
            // Respect a valid configuration supplied by the runtime.
            val existing = Os.getenv("FONTCONFIG_FILE")
            if (!existing.isNullOrBlank() && File(existing).isFile) return
            val root = File(context.filesDir, "subtitle-fontconfig").apply { mkdirs() }
            val cache = File(context.cacheDir, "subtitle-fontconfig").apply { mkdirs() }
            val fonts = File(context.filesDir, "mpv_fonts").apply { mkdirs() }
            fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
            val config = File(root, "fonts.conf")
            val content = """<?xml version="1.0"?>
                |<fontconfig>
                |  <dir>/system/fonts</dir>
                |  <dir>${xml(fonts.absolutePath)}</dir>
                |  <cachedir>${xml(cache.absolutePath)}</cachedir>
                |  <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans CJK KR</family></prefer></alias>
                |</fontconfig>
                |""".trimMargin()
            if (!config.isFile || config.readText() != content) config.writeText(content)
            Os.setenv("FONTCONFIG_FILE", config.absolutePath, true)
        } catch (error: Exception) {
            // Font configuration must never prevent the player from starting.
            Log.w("LilacMpv", "SUBTITLE_FONTCONFIG_SETUP_FAILED", error)
        }
    }
}
