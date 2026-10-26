package com.lilac.anime.data

import com.lilac.anime.WatchProgress
import com.lilac.anime.Anime
import com.lilac.anime.Episode

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
    fun orderedEpisodes(episodes: List<Episode>, key: String?): List<Episode> {
        val dubbed = key?.contains("_dub_ep_") == true
        val sameVersion = episodes.filter { ("_dub_ep_" in it.id) == dubbed }
        return (if (dubbed) sameVersion else sameVersion.ifEmpty { episodes }).distinctBy { it.id }
            .sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
    }
    fun availableEpisodes(episodes: List<Episode>, offline: Boolean, downloaded: (Episode) -> Boolean): List<Episode> =
        episodes.filter { it.playable && (!offline || downloaded(it)) }

    fun selectEpisode(episodes: List<Episode>, latest: WatchProgress?, requestedKey: String?): Episode? {
        val key = requestedKey ?: latest?.episodeKey
        val ordered = orderedEpisodes(episodes, key).filter { it.playable }
        val label = key?.let {
            if ("_ep_" in it || ":ep-" in it || Regex("\\d+[a-zA-Z]*").matches(it)) episodeLabel(it, latest?.episodeNumber ?: 0)
            else if (requestedKey == null && latest != null) episodeLabel(it, latest.episodeNumber) else null
        }
        val matched = ordered.firstOrNull { it.id == key }
            ?: ordered.firstOrNull { label != null && it.displayNumber.equals(label, true) }
            ?: (if (key == null) ordered.firstOrNull() else null)
            ?: return null
        return if (requestedKey == null && latest != null && fraction(latest.progress) >= .95f)
            ordered.getOrNull(ordered.indexOf(matched) + 1) ?: matched else matched
    }
    fun sameEpisode(key: String, number: Int, otherKey: String, otherNumber: Int, label: String? = null, otherLabel: String? = null): Boolean {
        if (number != otherNumber) return false
        if (key.equals(otherKey, true)) return true
        if (key.contains("_dub_ep_", true) != otherKey.contains("_dub_ep_", true)) return false
        return episodeLabel(label?.takeIf { it.isNotBlank() } ?: key, number) ==
            episodeLabel(otherLabel?.takeIf { it.isNotBlank() } ?: otherKey, otherNumber)
    }
    fun find(history: List<WatchProgress>, animeId: String, key: String, number: Int): WatchProgress? =
        history.firstOrNull { it.animeId == animeId && it.episodeKey.equals(key, true) }
            ?: history.firstOrNull { it.animeId == animeId && sameEpisode(key, number, it.episodeKey, it.episodeNumber) }
}
