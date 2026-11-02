package com.lilac.anime.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.lilac.anime.data.subtitle.translation.*
import com.lilac.anime.data.subtitle.translation.providers.GeminiTranslator
import com.lilac.anime.ui.settings.AiSettingsScreen
import com.lilac.anime.AppContextHolder
import com.lilac.anime.viewmodel.AnimeViewModel
import androidx.lifecycle.ViewModelProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class TranslationHttpIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<SubtitleTestActivity>()
    private val models="""{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]}]}"""
    @Test fun buttonReadsEncryptedKeyMakesHttpRequestParsesResponseAndShowsKorean() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val server=MockWebServer();server.start()
        try {
            SecureApiKeyStore.put(context,"gemini","fixture-key-not-a-real-credential")
            server.enqueue(MockResponse().setBody(models))
            val content=JSONArray().put(JSONObject().put("i",1).put("t","안녕하세요. 오늘 날씨가 좋네요."))
            val root=JSONObject().put("candidates",JSONArray().put(JSONObject().put("content",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",content.toString()))))))
            server.enqueue(MockResponse().setBody(root.toString()).setBodyDelay(500,TimeUnit.MILLISECONDS))
            val provider=GeminiTranslator(context,server.url("/").toString())
            AppContextHolder.init(context)
            val vm = ViewModelProvider(compose.activity)[AnimeViewModel::class.java]
            compose.setContent { MaterialTheme { AiSettingsScreen(vm, {}, translationProviderFactory = { id ->
                assertEquals("gemini", id)
                provider
            }) } }
            compose.onNodeWithText("Gemini", useUnmergedTree = true).performClick()
            compose.onNodeWithText("현재 선택: gemini").assertIsDisplayed()
            compose.onNodeWithText("번역 테스트 실행").performScrollTo()
            compose.onNodeWithText("번역 테스트 실행").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("번역 테스트 성공").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("안녕하세요. 오늘 날씨가 좋네요.").assertIsDisplayed()
            val modelRequest=server.takeRequest(2,TimeUnit.SECONDS)!!
            val translateRequest=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertTrue(modelRequest.path!!.startsWith("/models?"))
            assertTrue(translateRequest.path!!.contains(":generateContent"))
            assertEquals("fixture-key-not-a-real-credential",translateRequest.getHeader("x-goog-api-key"))
            assertTrue(translateRequest.body.readUtf8().contains("こんにちは"))
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try { saveSubtitleEvidence(context, "settings-http-success", screenshot) }
            finally { screenshot.recycle() }
        } finally { SecureApiKeyStore.remove(context,"gemini");server.shutdown() }
    }
    @Test fun realHttpErrorIsDisplayedInTheResultDialog() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val server=MockWebServer();server.start()
        try {
            SecureApiKeyStore.put(context,"gemini","fixture-key-not-a-real-credential")
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid fixture key"}}"""))
            val provider=GeminiTranslator(context,server.url("/").toString())
            AppContextHolder.init(context)
            val vm = ViewModelProvider(compose.activity)[AnimeViewModel::class.java]
            compose.setContent { MaterialTheme { AiSettingsScreen(vm, {}, translationProviderFactory = { id ->
                assertEquals("gemini", id)
                provider
            }) } }
            compose.onNodeWithText("Gemini", useUnmergedTree = true).performClick()
            compose.onNodeWithText("현재 선택: gemini").assertIsDisplayed()
            compose.onNodeWithText("번역 테스트 실행").performScrollTo()
            compose.onNodeWithText("번역 테스트 실행").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("번역 테스트 실패").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Gemini HTTP 401: invalid fixture key").assertIsDisplayed()
        } finally { SecureApiKeyStore.remove(context,"gemini");server.shutdown() }
    }
}
