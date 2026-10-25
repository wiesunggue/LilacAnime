package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.WatchProgress
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLDecoder
import java.security.MessageDigest

/** Wire format shared with LilacAnime-desktop/src/device-sync.js. No Android APIs. */
internal object DeviceSyncMapping {
    val collections = listOf("library", "history")
    fun wireAnimeId(id: String): String = if (':' in id) id else "linkani:$id"
    fun androidAnimeId(id: String): String? = when {
        id.substringBefore(':') in setOf("reanime", "miruro", "animenosub", "ohli24") -> id
        id.startsWith("linkani:") -> id.removePrefix("linkani:")
        else -> null // Keep unsupported PC providers in the wire cache, not in the Android UI.
    }
    fun fitId(raw: String): String {
        val id = Regex("%(21|27|28|29|2a)", RegexOption.IGNORE_CASE).replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        fun fits(s: String) = s.isNotEmpty() && s.length <= 512 && '\n' !in s
        if (fits(id)) return id
        // URLDecoder treats '+' as a space, unlike decodeURIComponent on the PC.
        val decoded = runCatching { URLDecoder.decode(id.replace("+", "%2B"), "UTF-8") }.getOrNull()
        if (decoded != null && fits(decoded)) return decoded
        var hash = 0x811c9dc5u
        id.toByteArray(Charsets.UTF_8).forEach { hash = (hash xor it.toUByte().toUInt()) * 0x01000193u }
        return "${id.substringBefore(':', "")}:#${hash.toString(36)}"
    }
    fun episodeLabel(w: WatchProgress): String = w.episodeKey.substringAfterLast("_ep_")
        .takeIf { Regex("\\d+[a-zA-Z]*").matches(it) } ?: w.episodeNumber.toString()

    private fun component(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")
    fun historyId(w: WatchProgress, anime: Anime? = null): String {
        val wire = wireAnimeId(w.animeId)
        val provider = wire.substringBefore(':'); val raw = wire.substringAfter(':')
        val label = episodeLabel(w)
        val episode = anime?.episodes?.firstOrNull { it.id == w.episodeKey || it.displayNumber.equals(label, true) }
        val sourceUrl = episode?.videoUrl?.takeIf { it.startsWith("https://") && !it.contains(".m3u8", true) && !it.contains(".mp4", true) }
        val url = sourceUrl ?: when (provider) {
            "reanime" -> "https://reanime.to/watch/${component(raw)}?ep=${w.episodeNumber}"
            "miruro" -> "https://www.miruro.to/watch/${component(raw)}?ep=${w.episodeNumber}"
            "animenosub" -> "https://animenosub.to/$raw-episode-$label${if ("_dub_ep_" in w.episodeKey) "-dub" else ""}/"
            "ohli24" -> "https://ani.ohli24.com/e/${component(w.episodeKey.removePrefix(w.animeId + ":"))}"
            else -> "https://linkani.tv/watch/$raw/a1/k$label/"
        }
        return fitId("$provider:$url")
    }
    private fun animeData(id: String, anime: Anime?, previous: JSONObject?): JSONObject {
        val wireId = wireAnimeId(id)
        val out = JSONObject(previous?.toString() ?: "{}")
        val provider = wireId.substringBefore(':')
        out.put("provider", provider).put("id", wireId.substringAfter(':')).put("mal_id", wireId)
        if (anime != null && anime.title.isNotBlank()) {
            out.put("title", anime.title)
            for ((key, value) in mapOf("title_english" to anime.english, "title_japanese" to anime.native,
                "synopsis" to anime.description, "year" to anime.year, "type" to anime.format)) {
                if (value.isNotBlank()) out.put(key, value)
            }
            if (anime.poster.isNotBlank()) {
                val images = out.optJSONObject("images") ?: JSONObject()
                val webp = images.optJSONObject("webp") ?: JSONObject()
                webp.put("large_image_url", anime.poster); images.put("webp", webp); out.put("images", images)
            }
        } else if (out.optString("title").isBlank()) out.put("title", id)
        if (!anime?.detailUrl.isNullOrBlank()) out.put("url", anime!!.detailUrl)
        if (!out.has("url")) {
            val raw = component(wireId.substringAfter(':'))
            out.put("url", when (provider) {
                "reanime" -> "https://reanime.to/anime/$raw"
                "miruro" -> "https://www.miruro.to/watch/$raw"
                "animenosub" -> "https://animenosub.to/anime/$raw/"
                "ohli24" -> "https://ani.ohli24.com/c/$raw"
                else -> "https://linkani.tv/ani/$raw/"
            })
        }
        return out
    }
    fun local(library: Set<String>, history: List<WatchProgress>, metadata: Map<String, Anime>, previous: JSONObject, addedAt: Map<String, Long> = emptyMap()): JSONObject {
        val lib = JSONObject(); val hist = JSONObject()
        library.forEach { id ->
            val wire = wireAnimeId(id)
            val data = animeData(id, metadata[id], previous.optJSONObject("library")?.optJSONObject(wire))
            if (addedAt.containsKey(id)) data.put("savedAt", addedAt.getValue(id))
            if (!data.has("savedAt")) data.put("savedAt", 1L) // Legacy favorites must not resurrect a newer deletion.
            lib.put(wire, data)
        }
        history.forEach { w ->
            val generated = historyId(w, metadata[w.animeId])
            val old = previous.optJSONObject("history") ?: JSONObject()
            // Retain the PC's exact episode URL/key, including source-specific suffixes.
            val existingKey = old.keys().asSequence().firstOrNull { key ->
                historyEntry(key, old.optJSONObject(key) ?: JSONObject())?.let { it.animeId == w.animeId && it.episodeKey == w.episodeKey } == true
            }
            val key = existingKey ?: generated
            val data = JSONObject(old.optJSONObject(key)?.toString() ?: "{}")
            data.put("key", key).put("androidAnimeId", w.animeId).put("androidEpisodeKey", w.episodeKey)
                .put("name", "${metadata[w.animeId]?.title ?: data.optJSONObject("anime")?.optString("title") ?: w.animeId} · ${episodeLabel(w)}화")
                .put("anime", animeData(w.animeId, metadata[w.animeId], data.optJSONObject("anime")))
                .put("progress", PlaybackHistory.percent(w.progress)).put("progressUnit", "percent")
            val oldData = old.optJSONObject(key)
            val legacy = oldData?.has("androidAnimeId") == true && !oldData.has("progressUnit")
            val changedAt = maxOf(w.updated.coerceAtLeast(1L), if (legacy) oldData!!.optLong("updated", 1L) + 1L else 1L)
            data.put("updated", changedAt)
            if (!data.has("episode")) data.put("episode", JSONObject().put("number", w.episodeNumber).put("name", episodeLabel(w))
                .put("provider", wireAnimeId(w.animeId).substringBefore(':')).put("url", key.substringAfter(':')))
            hist.put(key, data)
        }
        // Never publish deletions for providers that this Android build cannot represent.
        for (c in collections) {
            val old = previous.optJSONObject(c) ?: continue
            val target = if (c == "library") lib else hist
            old.keys().forEach { id ->
                val data = old.optJSONObject(id) ?: return@forEach
                val supported = if (c == "library") androidAnimeId(id) != null else historyEntry(id, data) != null
                if (!supported) target.put(id, data)
            }
        }
        return JSONObject().put("library", lib).put("history", hist)
    }
    fun historyEntry(id: String, data: JSONObject): WatchProgress? {
        val anime = data.optJSONObject("anime") ?: JSONObject()
        val wireId = anime.optString("mal_id").ifBlank {
            val raw = anime.optString("id")
            if (':' in raw) raw else "${anime.optString("provider", id.substringBefore(':'))}:$raw"
        }
        val animeId = data.optString("androidAnimeId").ifBlank { androidAnimeId(wireId) ?: return null }
        if (animeId.isBlank()) return null
        val episode = data.optJSONObject("episode") ?: JSONObject()
        val number = episode.optInt("number", data.optInt("episodeNumber", 1)).coerceAtLeast(1)
        val label = episode.optString("name").takeIf { Regex("\\d+[a-zA-Z]*").matches(it) } ?: number.toString()
        val key = data.optString("androidEpisodeKey").ifBlank {
            when {
                animeId.startsWith("reanime:") -> "$animeId:ep-$number"
                animeId.startsWith("miruro:") -> "$animeId:$number"
                animeId.startsWith("ohli24:") -> "$animeId:" + runCatching { URLDecoder.decode(episode.optString("url").substringAfter("/e/").substringBefore('?').trimEnd('/').replace("+", "%2B"), "UTF-8") }.getOrDefault(number.toString())
                else -> "${animeId}_${if (episode.optBoolean("dub")) "dub_" else ""}ep_$label"
            }
        }
        return WatchProgress(animeId, number, PlaybackHistory.fromWire(data.optDouble("progress", 0.0), data.has("androidAnimeId") && !data.has("progressUnit")),
            key, data.optLong("updated", 1L))
    }
    fun history(local: JSONObject): List<WatchProgress> {
        val obj = local.optJSONObject("history") ?: JSONObject()
        return obj.keys().asSequence().mapNotNull { id -> historyEntry(id, obj.optJSONObject(id) ?: JSONObject()) }
            .sortedByDescending { it.updated }.distinctBy { "${it.animeId}|${it.episodeKey}" }.take(300).toList()
    }
    /** Older builds kept these providers only in the wire cache. Seed them
     * before planning deletions when upgrading, or an empty old UI would erase
     * the PC's favorites/history on the first automatic sync. */
    fun upgradeCollections(localLibrary: Set<String>, localHistory: List<WatchProgress>, previous: JSONObject): Pair<Set<String>, List<WatchProgress>> {
        val newlySupported = setOf("miruro", "animenosub", "ohli24")
        val restoredLibrary = library(previous).filter { it.substringBefore(':') in newlySupported }
        val restoredHistory = history(previous).filter { it.animeId.substringBefore(':') in newlySupported }
        val mergedHistory = (localHistory + restoredHistory).sortedByDescending { it.updated }
            .distinctBy { "${it.animeId}|${it.episodeKey}" }.take(300)
        return (localLibrary + restoredLibrary) to mergedHistory
    }
    fun library(local: JSONObject): Set<String> = local.optJSONObject("library")?.keys()?.asSequence()?.mapNotNull(::androidAnimeId)?.toSet() ?: emptySet()
    fun animeMetadata(local: JSONObject): Map<String, Anime> {
        val result = linkedMapOf<String, Anime>()
        fun add(data: JSONObject) {
            val wire = data.optString("mal_id").ifBlank { "${data.optString("provider")}:${data.optString("id")}" }
            val id = androidAnimeId(wire) ?: return
            val poster = data.optJSONObject("images")?.optJSONObject("webp")?.optString("large_image_url").orEmpty()
            result[id] = Anime(id = id, title = data.optString("title", id), poster = poster,
                backdrop = poster, description = data.optString("synopsis"), year = data.optString("year"),
                english = data.optString("title_english"), native = data.optString("title_japanese"),
                source = if (wire.startsWith("linkani:")) "linkkf" else wire.substringBefore(':'), detailUrl = data.optString("url"))
        }
        local.optJSONObject("history")?.let { obj -> obj.keys().forEach { id -> obj.optJSONObject(id)?.optJSONObject("anime")?.let(::add) } }
        local.optJSONObject("library")?.let { obj -> obj.keys().forEach { id -> obj.optJSONObject(id)?.let(::add) } }
        return result
    }

    private fun stable(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + stable(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { stable(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    fun hash(value: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(stable(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun hashes(local: JSONObject): JSONObject = JSONObject().also { out ->
        collections.forEach { c ->
            val values = JSONObject(); val obj = local.optJSONObject(c) ?: JSONObject()
            obj.keys().forEach { id -> values.put(id, hash(obj.getJSONObject(id))) }; out.put(c, values)
        }
    }
    fun merge(local: JSONObject, incoming: List<JSONObject>): JSONObject {
        val result = JSONObject(local.toString())
        // Response order is authoritative. A tombstone may carry no data.
        incoming.forEach { item ->
            val c = item.optString("collection"); if (c !in collections) return@forEach
            val values = result.optJSONObject(c) ?: JSONObject().also { result.put(c, it) }
            val id = item.getString("id")
            if (item.optBoolean("deleted")) values.remove(id) else values.put(id, item.getJSONObject("data"))
        }
        return result
    }
}
