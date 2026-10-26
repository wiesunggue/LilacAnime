package com.lilac.anime.data.offline

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** Keep old, identified downloads readable; new IDs get a collision-resistant directory. */
internal object OfflinePathKey {
    fun legacy(key: String): String = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "_").take(180)
    fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return legacy(key).take(60) + "_" + digest
    }
    fun directory(root: File, animeId: String, episodeId: String): File {
        val key = "$animeId::$episodeId"
        val old = File(root, legacy("${animeId}__${episodeId}"))
        val storedId = runCatching { JSONObject(File(old, "metadata.json").readText()).optString("id") }.getOrNull()
        return if (storedId == key) old else File(root, fileName(key))
    }
}
