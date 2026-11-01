package com.lilac.anime.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lilac.anime.ui.settings.TranslationTestPanel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class TranslationTestUiTest {
    @get:Rule val compose = createAndroidComposeRule<SubtitleTestActivity>()
    @Test fun delayedSuccessIsShownInDialog() {
        val gate = CompletableDeferred<Unit>()
        compose.setContent { MaterialTheme { TranslationTestPanel("gemini") { gate.await(); Result.success("안녕하세요. 오늘 날씨가 좋네요.") } } }
        compose.onNodeWithText("번역 테스트 실행").performClick()
        compose.onNodeWithText("번역 테스트 진행 중 · gemini").assertIsDisplayed()
        compose.onNodeWithText("취소").assertIsEnabled()
        gate.complete(Unit)
        compose.waitUntil(3000) { compose.onAllNodesWithText("번역 테스트 성공").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("안녕하세요. 오늘 날씨가 좋네요.").assertIsDisplayed()
    }
    @Test fun failureIsVisibleAndRetryIsEnabled() {
        compose.setContent { MaterialTheme { TranslationTestPanel("gemini") { Result.failure(IllegalStateException("HTTP 429 사용량 초과")) } } }
        compose.onNodeWithText("번역 테스트 실행").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("번역 테스트 실패").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("HTTP 429 사용량 초과").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("번역 테스트 실행").assertIsEnabled()
    }
    @Test fun unchangedLocalOutputIsReportedAsFailure() {
        compose.setContent { MaterialTheme { TranslationTestPanel("local") { Result.success(it) } } }
        compose.onNodeWithText("번역 테스트 실행").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("번역 테스트 실패").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("번역 결과가 원문과 같습니다. 모델이 번역문을 반환하지 않았습니다.").assertIsDisplayed()
    }
    @Test fun hungRequestCanBeCancelled() {
        val gate = CompletableDeferred<Unit>()
        compose.setContent { MaterialTheme { TranslationTestPanel("gemini") { gate.await(); Result.success("늦은 결과") } } }
        compose.onNodeWithText("번역 테스트 실행").performClick()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("번역 테스트 취소").assertIsDisplayed()
        gate.complete(Unit)
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("번역 테스트 실행").assertIsEnabled()
        compose.onNodeWithText("늦은 결과").assertDoesNotExist()
    }
    @Test fun timeoutShowsErrorRatherThanSilentlyResetting() {
        compose.setContent { MaterialTheme { TranslationTestPanel("gemini", timeoutMs = 150) { CompletableDeferred<String>().await(); Result.success("unused") } } }
        compose.onNodeWithText("번역 테스트 실행").performClick()
        compose.waitUntil(3000) { compose.onAllNodesWithText("번역 테스트 실패").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("응답 시간이 초과되었습니다. 네트워크, API 사용량 또는 로컬 모델 실행 상태를 확인하세요.").assertIsDisplayed()
    }
}
