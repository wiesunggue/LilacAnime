package com.lilac.anime.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilac.anime.data.subtitle.translation.SubtitleMarkup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.PatternSyntaxException

@RunWith(AndroidJUnit4::class)
class SubtitleMarkupAndroidTest {
    @Test fun oldJvmOnlyPatternFailsOnAndroidAndProductionPatternWorks() {
        // This compiles on host Java/Robolectric, but not Android's ICU engine.
        assertThrows(PatternSyntaxException::class.java) { Regex("\\{\\\\[^{}]*}") }
        assertEquals("안녕\n세계", SubtitleMarkup.plainText("{\\an8}<font color='red'>안녕</font><br>세계"))
        assertEquals("Hello", SubtitleMarkup.plainText("Hello"))
    }
}
