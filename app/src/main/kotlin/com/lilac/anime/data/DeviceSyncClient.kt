package com.lilac.anime.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lilac.anime.Anime
import com.lilac.anime.WatchProgress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import com.lilac.anime.data.offline.OfflineStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device sync client for the user's own LilacAnime server. Passwords are never persisted. */
object DeviceSyncClient {
    private const val PREFS = "lilac_device_sync"
    private const val KEY_ALIAS = "lilacanime_device_sync_token_v1"
    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "encrypted_token"
    private const val KEY_REV = "revision"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val MAX_BATCH = 500
    private const val KEY_DATA = "wire_data_v2"
    private const val KEY_SCHEMA = "wire_schema"
    private const val KEY_ADDED = "library_added_at"
    private val syncMutex = Mutex()

    data class SyncResult(val library: Set<String>, val history: List<WatchProgress>, val sent: Int, val received: Int, val revision: Long)

    fun savedServer(context: Context): String = prefs(context).getString(KEY_URL, "") ?: ""
    fun isConnected(context: Context): Boolean = decryptToken(context).isNotBlank()

    class RequestException(val code: Int, message: String) : IllegalStateException(message)
    fun automaticSyncEnabled(context: Context): Boolean = prefs(context).getBoolean("automatic_sync", true)
    fun setAutomaticSyncEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("automatic_sync", enabled).apply() }
    fun cachedAnime(context: Context): Map<String, Anime> = runCatching {
        DeviceSyncMapping.animeMetadata(JSONObject(prefs(context).getString(KEY_DATA, "{}") ?: "{}"))
    }.getOrElse { emptyMap() }

    fun libraryAdded(context: Context, id: String) {
        val p = prefs(context)
        val added = runCatching { JSONObject(p.getString(KEY_ADDED, "{}") ?: "{}") }.getOrElse { JSONObject() }
        added.put(id, System.currentTimeMillis())
        p.edit().putString(KEY_ADDED, added.toString()).apply()
    }

    fun disconnect(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_REV).remove(KEY_SNAPSHOT).remove(KEY_DATA).remove(KEY_SCHEMA).apply()
    }

    fun connect(context: Context, rawUrl: String, password: String): String {
        val base = normalizeUrl(rawUrl)
        val status = request("GET", "$base/api/status", null, null)
        if (status.optString("app") != "lilacanime-server") throw IllegalStateException("LilacAnime 동기화 서버가 아닙니다.")
        if (!status.optBoolean("ready", false)) throw IllegalStateException(status.optJSONArray("problems")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } } ?: "서버 설정이 완료되지 않았습니다.")
        if (password.length < 8) throw IllegalArgumentException("서버 비밀번호는 8자 이상이어야 합니다.")
        val login = request("POST", "$base/api/login", JSONObject().put("password", password), null)
        val token = login.optString("token").takeIf { it.isNotBlank() } ?: throw IllegalStateException(login.optString("error", "로그인 토큰을 받지 못했습니다."))
        val editor = prefs(context).edit().putString(KEY_URL, base).putString(KEY_TOKEN, encryptToken(context, token))
            .remove(KEY_REV).remove(KEY_SNAPSHOT)
        if (savedServer(context) != base) editor.remove(KEY_DATA)
        editor.putInt(KEY_SCHEMA, 2).apply()
        return "서버 연결 완료"
    }

    suspend fun sync(
        context: Context,
        library: Set<String>,
        history: List<WatchProgress>,
        metadata: Map<String, Anime> = emptyMap(),
        readFresh: () -> Pair<Set<String>, List<WatchProgress>> = { library to history },
        apply: suspend (Set<String>, List<WatchProgress>) -> Unit = { _, _ -> },
    ): SyncResult = syncMutex.withLock { withContext(Dispatchers.IO) {
        val base = savedServer(context)
        val token = decryptToken(context)
        require(base.isNotBlank() && token.isNotBlank()) { "먼저 동기화 서버에 연결하세요." }
        val p = prefs(context)
        val migrated = p.getInt(KEY_SCHEMA, 0) == 2
        val previous = runCatching { JSONObject(p.getString(KEY_DATA, "{}") ?: "{}") }.getOrElse { JSONObject() }
        val before = if (migrated) runCatching { JSONObject(p.getString(KEY_SNAPSHOT, "{}") ?: "{}") }.getOrElse { JSONObject() } else JSONObject()
        val added = runCatching { JSONObject(p.getString(KEY_ADDED, "{}") ?: "{}") }.getOrElse { JSONObject() }
        val addedAt = added.keys().asSequence().associateWith { added.optLong(it, 1L) }
        val local = DeviceSyncMapping.local(library, history, metadata, previous, addedAt)
        val outgoing = mutableListOf<JSONObject>()
        val now = System.currentTimeMillis()
        for (c in DeviceSyncMapping.collections) {
            val current = local.getJSONObject(c)
            val old = before.optJSONObject(c) ?: JSONObject()
            current.keys().forEach { id ->
                val data = current.getJSONObject(id)
                if (old.optString(id) != DeviceSyncMapping.hash(data)) {
                    val time = if (c == "history") data.optLong("updated", 1L) else if (old.has(id)) now else data.optLong("savedAt", 1L)
                    outgoing += JSONObject().put("collection", c).put("id", id).put("updated", time.coerceAtLeast(1L)).put("data", data)
                }
            }
            old.keys().forEach { id ->
                if (!current.has(id)) outgoing += JSONObject().put("collection", c).put("id", id).put("updated", now).put("deleted", true)
            }
        }
        // Enforce server limits before the first request, with a byte budget as
        // well as an item count (500 detailed anime can exceed Vercel's body limit).
        require(outgoing.all { it.toString().toByteArray(Charsets.UTF_8).size <= 64 * 1024 }) { "동기화 항목 하나가 서버의 64KB 제한을 넘었습니다." }
        val incoming = mutableListOf<JSONObject>()
        var sent = 0
        var batch = JSONArray(); var bytes = 0
        fun sendBatch() {
            if (batch.length() == 0) return
            val response = request("POST", "$base/api/sync", JSONObject().put("items", batch), token)
            sent += response.getInt("accepted")
            val current = response.getJSONArray("current")
            for (i in 0 until current.length()) incoming += current.getJSONObject(i)
            batch = JSONArray(); bytes = 0
        }
        outgoing.forEach { item ->
            val size = item.toString().toByteArray(Charsets.UTF_8).size
            if (batch.length() >= MAX_BATCH || bytes + size > 3 * 1024 * 1024) sendBatch()
            batch.put(item); bytes += size
        }
        sendBatch()
        var rev = if (migrated) p.getLong(KEY_REV, 0L) else 0L
        var more = true; var received = 0
        while (more) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = request("GET", "$base/api/sync?since=$rev", null, token)
            val items = page.getJSONArray("items")
            val next = page.getLong("rev")
            more = page.getBoolean("more")
            require(next >= rev && (!more || next > rev)) { "서버가 올바른 다음 리비전을 제공하지 않았습니다. 다시 연결하세요." }
            require(!more || items.length() > 0) { "서버의 다음 페이지가 비어 있습니다." }
            for (i in 0 until items.length()) { incoming += items.getJSONObject(i); received++ }
            rev = next
        }
        require(savedServer(context) == base && decryptToken(context) == token) { "동기화 중 서버 연결이 변경되었습니다. 다시 시도하세요." }
        val remote = DeviceSyncMapping.merge(local, incoming)
        val remoteLibrary = DeviceSyncMapping.library(remote)
        val remoteHistory = DeviceSyncMapping.history(remote)
        // The snapshot describes the acknowledged state, not edits made during
        // the network request. Those edits stay pending for the next sync.
        val acknowledged = DeviceSyncMapping.local(remoteLibrary, remoteHistory, metadata, remote, addedAt)
        val finalState = withContext(Dispatchers.Main) {
            val (freshLibrary, freshHistory) = readFresh()
            val finalLibrary = (remoteLibrary + (freshLibrary - library)) - (library - freshLibrary)
            val initial = history.associateBy { "${it.animeId}|${it.episodeKey}" }
            val fresh = freshHistory.associateBy { "${it.animeId}|${it.episodeKey}" }
            val combined = remoteHistory.associateBy { "${it.animeId}|${it.episodeKey}" }.toMutableMap()
            (initial.keys + fresh.keys).forEach { key ->
                if (initial[key] != fresh[key]) {
                    val changed = fresh[key]
                    if (changed == null) combined.remove(key) else combined[key] = changed
                }
            }
            val finalHistory = combined.values.sortedByDescending { it.updated }.take(300)
            apply(finalLibrary, finalHistory)
            finalLibrary to finalHistory
        }
        OfflineStore.saveLibrary(context, finalState.first)
        OfflineStore.saveWatchHistory(context, finalState.second)
        p.edit().putInt(KEY_SCHEMA, 2).putLong(KEY_REV, rev)
            .putString(KEY_DATA, remote.toString()).putString(KEY_SNAPSHOT, DeviceSyncMapping.hashes(acknowledged).toString()).apply()
        SyncResult(finalState.first, finalState.second, sent, received, rev)
    } }

    private fun normalizeUrl(raw: String): String {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("올바른 서버 주소를 입력하세요.") }
        require(uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank()) { "서버 주소는 http:// 또는 https://로 시작해야 합니다." }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "서버의 기본 주소만 입력하세요. (예: https://이름.vercel.app)" }
        if (uri.scheme.equals("http", true)) require(uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]")) { "외부 서버는 HTTPS 주소를 사용하세요." }
        return java.net.URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null).toString()
    }

    private fun request(method: String, url: String, body: JSONObject?, token: String?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; instanceFollowRedirects = false; connectTimeout = 12000; readTimeout = 20000; setRequestProperty("Accept", "application/json"); if (token != null) setRequestProperty("Authorization", "Bearer $token"); if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") } }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) {
                throw IllegalStateException(if (code in 300..399 || text.contains("Vercel", true)) "Vercel 보호 페이지입니다. Domains의 고정 주소와 Deployment Protection 설정을 확인하세요." else "서버가 JSON 응답을 반환하지 않았습니다. (HTTP $code)")
            }
            if (code !in 200..299) throw RequestException(code, json.optString("error").ifBlank { "서버 요청 실패 (HTTP $code)" })
            return json
        } finally { conn.disconnect() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    private fun encryptToken(context: Context, token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8)); return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }
    private fun decryptToken(context: Context): String {
        val stored = prefs(context).getString(KEY_TOKEN, null) ?: return ""
        return try { val raw = Base64.decode(stored, Base64.NO_WRAP); val iv = raw.copyOfRange(0, 12); val data = raw.copyOfRange(12, raw.size); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); String(cipher.doFinal(data), Charsets.UTF_8) } catch (_: Exception) { prefs(context).edit().remove(KEY_TOKEN).apply(); "" }
    }
}
