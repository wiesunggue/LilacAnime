package com.lilac.anime.data

import com.lilac.anime.WatchProgress
import com.lilac.anime.Anime

/** Android stores a fraction; the desktop/server protocol stores a percentage. */
internal object PlaybackHistory {
    fun fraction(value: Float): Float = if (!value.isFinite()) 0f else (if (value > 1f) value / 100f else value).coerceIn(0f, 1f)
    fun percent(value: Float): Double = fraction(value).toDouble() * 100.0
    fun fromWire(value: Double, legacyAndroid: Boolean): Float =
        if (!value.isFinite()) 0f else (if (legacyAndroid && value <= 1.0) value else value / 100.0).toFloat().coerceIn(0f, 1f)

    fun episodeLabel(key: String, number: Int): String {
        val last = key.substringAfterLast("_ep_").substringAfterLast(":ep-")
        return last.takeIf { Regex("\\d+[a-zA-Z]*").matches(it) }?.lowercase() ?: number.toString()
    }
    fun sourceFor(anime: Anime, fallback: String): String {
        val known = setOf("reanime", "miruro", "animenosub", "ohli24", "linkkf")
        val prefix = anime.id.substringBefore(':')
        return when {
            prefix in known -> prefix
            anime.source in known -> anime.source
            anime.id.all { it.isDigit() } || anime.detailUrl.contains("linkani.tv") -> "linkkf"
            else -> fallback
        }
    }
    fun resumePosition(durationMs: Long, progress: Float): Long {
        val value = fraction(progress)
        if (durationMs <= 250 || value <= 0f || value >= .95f) return 0L
        return (durationMs * value.toDouble()).toLong().coerceIn(0L, durationMs - 250L)
    }
    fun find(history: List<WatchProgress>, animeId: String, key: String, number: Int): WatchProgress? =
        history.firstOrNull { it.animeId == animeId && it.episodeKey.equals(key, true) }
            ?: history.firstOrNull { it.animeId == animeId && it.episodeNumber == number &&
                episodeLabel(it.episodeKey, it.episodeNumber) == episodeLabel(key, number) }
}
