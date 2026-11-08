package com.lilac.anime.player

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lilac.anime.AppContextHolder
import com.lilac.anime.MainActivity
import org.junit.Rule
import org.junit.Test

/** Real MainActivity, including startup and the normal navigation graph. */
class AppNavigationSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    // Material NavigationBarItem clears the decorative icon's description
    // when a visible label is present. Select the labelled clickable item.
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    @Test fun homeHistoryLibrarySettingsAndDownloadsRemainNavigable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppContextHolder.init(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                compose.waitUntil(60000) { compose.onAllNodesWithText("홈").fetchSemanticsNodes().isNotEmpty() }
                repeat(2) {
                    tab("시청기록").performClick()
                    compose.onNodeWithText("시청 기록 검색").assertIsDisplayed()
                    tab("내 목록").performClick()
                    compose.onNodeWithText("제목 · 원제 · 장르 검색").assertIsDisplayed()
                    tab("설정").performClick()
                    compose.onNodeWithText("다운로드 관리 · 일시정지 / 이어받기 / 재시도").performScrollTo().performClick()
                    compose.onNodeWithText("다운로드 제목 · 회차 검색").assertIsDisplayed()
                    compose.onNodeWithText("실패", substring = false).performClick().assertIsSelected()
                    compose.onNodeWithContentDescription("뒤로").performClick()
                    tab("홈").performClick().assertIsSelected()
                }
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try { saveSubtitleEvidence(context, "app-navigation-home", screenshot) }
                finally { screenshot.recycle() }
            } catch (failure: Throwable) {
                compose.onRoot().printToLog("NavigationFailure")
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try { saveSubtitleEvidence(context, "app-navigation-failure", screenshot) }
                finally { screenshot.recycle() }
                throw failure
            }
        }
    }
}
