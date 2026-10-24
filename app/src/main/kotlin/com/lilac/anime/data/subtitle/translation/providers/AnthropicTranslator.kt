package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Claude/Anthropic subtitle translator, matching the desktop provider without requiring the desktop SDK. */
class AnthropicTranslator(private val context: Context) : TranslationProvider {
    override val id = "claude"
    override val displayName = "Claude"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build()

    @Volatile private var modelCache: List<String> = emptyList()
    @Volatile private var modelCacheKey: String = ""
    @Volatile private var modelCacheAt: Long = 0L

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        val models = listModels(key)
        if (models.isEmpty()) error("이 Anthropic API 키로 사용할 수 있는 Claude 모델이 없습니다.")
        "연결 성공: ${defaultModel(models)} (사용 가능 모델 ${models.size}개)"
    }

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = apiKey()
        val available = listModels(key)
        if (available.isEmpty()) error("이 Anthropic API 키로 사용할 수 있는 Claude 모델이 없습니다.")

        val configured = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .getString("pref_claude_model", "")
            .orEmpty()
            .trim()
        val chain = buildList {
            if (configured.isNotBlank() && configured in available) add(configured)
            add(defaultModel(available))
            addAll(available.filter { it.contains("sonnet", true) })
            addAll(available.filter { it.contains("haiku", true) })
            addAll(available)
        }.filter { it.isNotBlank() }.distinct()

        var last: Throwable? = null
        for (model in chain) {
            try {
                return request(key, model, lines)
            } catch (error: Throwable) {
                last = error
                if (!isSwitchable(error)) throw error
            }
        }
        throw last ?: error("Claude 번역에 실패했습니다.")
    }

    private suspend fun request(key: String, model: String, lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        // Marker output is deliberately requested even though CloudTranslationText
        // also accepts JSON. It works across old/new Claude model families without
        // depending on a beta structured-output header.
        val system = CloudTranslationPrompt.config(context).system +
            "\n각 입력 항목 앞의 <LILAC_N> 번호를 그대로 유지하고, 각 번호 뒤에는 번역문만 출력하세요."
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 16000)
            .put("temperature", 0.3)
            .put("system", system)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", CloudTranslationPrompt.userMessage(context, lines))
            ))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, raw)
            val root = JSONObject(raw)
            if (root.optString("stop_reason") == "max_tokens") {
                error("Claude의 답이 길이 제한에서 끊겼습니다.")
            }
            val content = root.optJSONArray("content") ?: JSONArray()
            val text = buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }.trim()
            if (text.isBlank()) error("Claude 응답에 번역 텍스트가 없습니다.")
            CloudTranslationText.parseMarked(text, lines)
        }
    }

    private fun apiKey(): String =
        SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Anthropic API Key가 없습니다. AI 설정에서 키를 저장하세요.")

    private fun listModels(key: String): List<String> {
        val fingerprint = key.hashCode().toString()
        val now = System.currentTimeMillis()
        if (modelCache.isNotEmpty() && modelCacheKey == fingerprint && now - modelCacheAt < 15 * 60 * 1000L) {
            return modelCache
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=100")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        val loaded = client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, raw)
            val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id").orEmpty().trim()
                    if (id.startsWith("claude-", true)) add(id)
                }
            }.distinct().sortedWith(compareBy<String> {
                when {
                    it.contains("sonnet", true) -> 0
                    it.contains("haiku", true) -> 1
                    it.contains("opus", true) -> 2
                    else -> 3
                }
            }.thenByDescending { it })
        }
        if (loaded.isNotEmpty()) {
            modelCache = loaded
            modelCacheKey = fingerprint
            modelCacheAt = now
        }
        return loaded
    }

    private fun defaultModel(models: List<String>): String =
        models.firstOrNull { it.contains("sonnet", true) }
            ?: models.firstOrNull { it.contains("haiku", true) }
            ?: models.firstOrNull().orEmpty()

    private fun apiError(code: Int, raw: String): Throwable {
        val message = runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return IllegalStateException("Claude HTTP $code${if (message.isNotBlank()) ": $message" else ""}")
    }

    private fun isSwitchable(error: Throwable): Boolean = error.message?.let { message ->
        message.contains("HTTP 400") || message.contains("HTTP 404") ||
            message.contains("HTTP 429") || message.contains("HTTP 500") ||
            message.contains("HTTP 502") || message.contains("HTTP 503")
    } == true
}
