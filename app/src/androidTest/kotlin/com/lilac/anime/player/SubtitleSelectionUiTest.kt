package com.lilac.anime.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.lilac.anime.data.subtitle.SubtitleDiscoveryService.Choice
import com.lilac.anime.data.subtitle.SubtitleDiscoveryService.Source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SubtitleSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<SubtitleTestActivity>()
    private val choices = listOf(Choice(Source.JIMAKU, "Jimaku", "일본어", "파일 A"), Choice(Source.REANIME, "Re:Anime", "영어", "파일 B"))
    @Test fun cancelIsClickableDuringSearch() {
        var cancelled = false
        compose.setContent { MaterialTheme { SubtitleSelectionDialog("테스트", emptyList(), null, true, false, false, null, {}, {}, {}, { cancelled = true }) } }
        compose.onNodeWithText("취소 · 자막 없이 재생").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun backCancelsDuringDownload() {
        var cancelled = false
        compose.setContent { MaterialTheme { SubtitleSelectionDialog("테스트", choices, choices[0].key, false, true, false, null, {}, {}, {}, { cancelled = true }) } }
        compose.waitForIdle(); pressBack()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun onlyOneFileIsSelectedAndTranslationDoesNotSelectAnother() {
        compose.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            var translate by remember { mutableStateOf(false) }
            MaterialTheme { SubtitleSelectionDialog("테스트", choices, selected, false, false, translate, null, { selected = it.key }, { translate = it }, {}, {}) }
        }
        compose.onNodeWithText("파일 A").performClick()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNodeWithText("파일 B").performClick()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(isToggleable()).performClick().assertIsOn()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNodeWithText("이 자막으로 재생").assertIsEnabled()
    }
}
