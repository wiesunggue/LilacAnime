package com.lilac.anime.data.subtitle.translation

import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SubtitleFileStyleTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun provider(received: MutableList<String> = mutableListOf()) = object : TranslationProvider {
        override val id = "gemini"
        override val displayName = "fixture"
        override suspend fun translateBatch(lines: List<String>): List<String> { received += lines; return lines.map { "번역문" } }
    }
    private suspend fun translate(text: String, ext: String, received: MutableList<String> = mutableListOf()): String? {
        val source = File.createTempFile("styled-source-", ".$ext", context.cacheDir).apply { writeText(text) }
        return try {
            SubtitleFormatTranslator.translate(context, source.path, source.nameWithoutExtension, provider(received))?.let { File(it).readText() }
        } finally { source.delete() }
    }
    @Test fun exportedSrtRetainsColourWeightAndOriginalTimestamps() = runBlocking {
        val source = "1\n00:00:00,000 --> 00:00:03,000\n<font color=\"#00ff00\"><b>Hello</b></font>"
        val output = translate(source, "srt")!!
        assertEquals(source.replace("Hello", "번역문"), output)
    }
    @Test fun exportedVttRetainsCueIdSettingsAndClassStyles() = runBlocking {
        val source = "WEBVTT\n\nSTYLE\n::cue(.green) { color: lime; }\n\ncue-one\n00:00.000 --> 00:03.000 line:10% align:start\n<c.green>Hello</c>"
        assertEquals(source.replace("Hello", "번역문"), translate(source, "vtt"))
    }
    private fun ass(text: String) = "[Script Info]\nScriptType: v4.00+\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour\nStyle: Default,Roboto,28,&H0000FF00\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:00.00,0:00:03.00,Default,Speaker,0,0,0,,${text}\n"
    @Test fun exportedAssDoesNotPutTheEndTransparencyOverrideBeforeAllTranslatedText() = runBlocking {
        val japanese = "あ".repeat(60)
        val source = ass("{\\c&H00FF00&}$japanese{\\alpha&HFF&}")
        val received = mutableListOf<String>()
        val output = translate(source, "ass", received)!!
        assertEquals(listOf(japanese), received)
        assertEquals(source.replace(japanese, "번역문"), output)
    }
    @Test fun englishAssDoesNotRequireJapaneseTextBeforeItCanBeTranslated() = runBlocking {
        val source = ass("Hello world")
        assertEquals(source.replace("Hello world", "번역문"), translate(source, "ass"))
    }
    @Test fun drawingWithSameTimestampsAsDialogueIsNeverReplacedWithTranslatedText() = runBlocking {
        val drawing = "Dialogue: 0,0:00:00.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 30 0 30 30{\\p0}"
        val source = ass("Hello world") + drawing + "\n"
        val received = mutableListOf<String>()
        val output = translate(source, "ass", received)!!
        assertEquals(listOf("Hello world"), received)
        assertTrue(output.contains(drawing))
        assertTrue(output.contains("번역문"))
    }

}
