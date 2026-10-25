package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.WatchProgress
import java.util.Locale

internal object CollectionQuery {
    val sources = linkedMapOf("all" to "모든 소스", "reanime" to "RE:Anime", "miruro" to "Miruro", "animenosub" to "Animenosub", "ohli24" to "애니24", "linkkf" to "링크애니")
    val sorts = linkedMapOf("saved" to "저장순", "title" to "제목순", "recent" to "최근 시청순", "year" to "연도순")
    val states = linkedMapOf("all" to "전체", "new" to "시청 전", "watching" to "시청 중", "finished" to "최근 회차 완주")
    /** Source switching may discard catalogs, but must retain cross-source saved/history metadata. */
    fun retainCollections(library: Set<String>, history: List<WatchProgress>, vararg catalogs: Collection<Anime>): Map<String, Anime> {
        val ids = library + history.map { it.animeId }
        return catalogs.flatMap { it }.filter { it.id in ids }.associateBy { it.id }
    }
    fun matches(anime: Anime, query: String): Boolean {
        val haystack = listOf(anime.title, anime.native, anime.romaji, anime.english, anime.synonyms, anime.genres.joinToString(" ")).joinToString(" ").lowercase(Locale.ROOT)
        return query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).all { it in haystack }
    }
    fun apply(anime: List<Anime>, history: List<WatchProgress>, query: String, source: String, state: String, sort: String): List<Anime> {
        val latest = history.groupBy { it.animeId }.mapValues { (_, entries) -> entries.maxBy { it.updated } }
        val filtered = anime.distinctBy { it.id }.filter { item ->
            val progress = latest[item.id]?.let { PlaybackHistory.fraction(it.progress) } ?: 0f
            matches(item, query) && (source == "all" || PlaybackHistory.sourceFor(item, "") == source) && when (state) {
                "new" -> progress <= 0f
                "watching" -> progress > 0f && progress < .95f
                "finished" -> progress >= .95f
                else -> true
            }
        }
        return when (sort) {
            "title" -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
            "recent" -> filtered.sortedByDescending { latest[it.id]?.updated ?: 0L }
            "year" -> filtered.sortedByDescending { it.seasonYear ?: Regex("\\d{4}").find(it.year)?.value?.toIntOrNull() ?: 0 }
            else -> filtered
        }
    }
    /** Filter before paging, so search also finds episodes beyond the current page. */
    fun episodes(episodes: List<Episode>, query: String, newestFirst: Boolean): List<Episode> {
        val sorted = episodes.sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber.lowercase(Locale.ROOT) })
        return (if (newestFirst) sorted.reversed() else sorted).filter {
            query.isBlank() || listOf(it.displayNumber, it.title, it.nativeTitle).any { value -> value.contains(query.trim(), true) }
        }
    }
}
