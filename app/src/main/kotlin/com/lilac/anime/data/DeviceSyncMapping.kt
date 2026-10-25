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
        id.startsWith("reanime:") -> id
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

    fun historyId(w: WatchProgress, anime: Anime? = null): String {
        val provider = wireAnimeId(w.animeId).substringBefore(':')
        val label = episodeLabel(w)
        val episode = anime?.episodes?.firstOrNull { it.id == w.episodeKey || it.displayNumber.equals(label, true) }
        val url = if (provider == "reanime") {
            "https://reanime.to/watch/${w.animeId.removePrefix("reanime:")}?ep=${w.episodeNumber}"
        } else episode?.videoUrl?.takeIf { it.startsWith("https://linkani.tv/watch/") }
            ?: "https://linkani.tv/watch/${w.animeId}/a1/k$label/"
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
        if (!out.has("url")) out.put("url", if (provider == "reanime") "https://reanime.to/anime/${wireId.substringAfter(':')}" else "https://linkani.tv/ani/$id/")
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
                .put("progress", w.progress.toDouble())
            if (w.updated > 0) data.put("updated", w.updated) else if (!data.has("updated")) data.put("updated", 1L)
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
            if (animeId.startsWith("reanime:")) "$animeId:ep-$number" else "${animeId}_ep_$label"
        }
        return WatchProgress(animeId, number, data.optDouble("progress", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 100f) ?: 0f,
            key, data.optLong("updated", 1L))
    }
    fun history(local: JSONObject): List<WatchProgress> {
        val obj = local.optJSONObject("history") ?: JSONObject()
        return obj.keys().asSequence().mapNotNull { id -> historyEntry(id, obj.optJSONObject(id) ?: JSONObject()) }
            .sortedByDescending { it.updated }.distinctBy { "${it.animeId}|${it.episodeKey}" }.take(300).toList()
    }
    fun library(local: JSONObject): Set<String> = local.optJSONObject("library")?.keys()?.asSequence()?.mapNotNull(::androidAnimeId)?.toSet() ?: emptySet()
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
