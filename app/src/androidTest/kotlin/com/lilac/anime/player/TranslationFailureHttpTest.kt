package com.lilac.anime.player

import androidx.test.platform.app.InstrumentationRegistry
import com.lilac.anime.data.subtitle.translation.RealtimeSubtitleTranslator
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import com.lilac.anime.data.subtitle.translation.providers.DeepLTranslator
import com.lilac.anime.data.subtitle.translation.providers.GeminiTranslator
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual provider HTTP/JSON/Android Keystore paths, with a local API fixture. */
class TranslationFailureHttpTest {
    @Test fun geminiOutageHasThreeRequestsAndExplicitRetryRecovers() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = File(context.cacheDir, "http-recovery.srt").apply { writeText("1\n00:00:00,000 --> 00:00:10,000\nHello") }
        val provider = GeminiTranslator(context, server.url("/").toString())
        val translator = RealtimeSubtitleTranslator(context, providerFactory = { provider }, hasApiKey = { false })
        try {
            SecureApiKeyStore.put(context, "gemini", "fixture-key-not-a-real-credential")
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]}]}"""))
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"fixture outage"}}""")) }
            translator.ensurePrepared(source.path, 100, scope, "gemini")
            withTimeout(30000) { while (translator.playbackProgress().error == null) delay(100) }
            delay(2500)
            assertEquals("One model listing and three generation attempts; no per-model/per-cue fan-out", 4, server.requestCount)
            assertEquals(0, translator.playbackProgress().completed)
            val generated = org.json.JSONArray().put(org.json.JSONObject().put("i", 1).put("t", "안녕하세요"))
            val root = org.json.JSONObject().put("candidates", org.json.JSONArray().put(org.json.JSONObject().put("content", org.json.JSONObject().put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", generated.toString()))))))
            server.enqueue(MockResponse().setBody(root.toString()))
            translator.retryFailedTranslations()
            withTimeout(15000) { while (translator.playbackProgress().completed != 1) delay(100) }
            assertNull(translator.playbackProgress().error)
            assertTrue(translator.nativePlaybackSnapshot()!!.content.contains("안녕하세요"))
            assertEquals(5, server.requestCount)
        } finally {
            translator.clear(); scope.cancel(); source.delete()
            SecureApiKeyStore.remove(context, "gemini"); server.shutdown()
        }
    }

    @Test fun deepLAuthenticationErrorSendsOnlyOneHttpRequest() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().apply { start() }
        try {
            SecureApiKeyStore.put(context, "deepl", "fixture-key-not-a-real-credential:fx")
            repeat(3) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"invalid fixture key"}""")) }
            val failure = runCatching { DeepLTranslator(context, server.url("/v2").toString()).translateBatch(listOf("Hello")) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("HTTP 401"))
            assertEquals(1, server.requestCount)
        } finally { SecureApiKeyStore.remove(context, "deepl"); server.shutdown() }
    }
}
