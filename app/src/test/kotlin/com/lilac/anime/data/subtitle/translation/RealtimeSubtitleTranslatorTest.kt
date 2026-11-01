package com.lilac.anime.data.subtitle.translation

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RealtimeSubtitleTranslatorTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lilac_offline_store", 0).edit().clear()
            .putInt("pref_ai_prefetch_ahead", 0).commit()
    }
    private class Provider(override val id: String = "gemini", val action: suspend (List<String>) -> List<String> = { lines -> lines.map { "번역 $it" } }) : TranslationProvider {
        override val displayName = id
        var calls = 0
        override suspend fun translateBatch(lines: List<String>): List<String> { calls++; return action(lines) }
    }
    private fun file(text: String, ext: String = "srt") = File.createTempFile("subtitle-", ".$ext", context.cacheDir).apply { writeText(text) }
    private fun srt(count: Int) = (0 until count).joinToString("\n\n") { i ->
        val seconds = i * 2
        fun time(value: Int) = "%02d:%02d:%02d,000".format(value / 3600, value / 60 % 60, value % 60)
        "${i+1}\n${time(seconds)} --> ${time(seconds+1)}\nLine $i"
    }
    @Test fun nativeAssSnapshotPreservesUntranslatedEventsAndStyles() = runTest {
        val content = "[Script Info]\nScriptType: v4.00+\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,...\n" +
            "Dialogue: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,Hello"
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        translator.playbackTick(file(content, "ass").path, 2100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        val snapshot = translator.nativeTrackSnapshot()!!
        assertTrue(snapshot.contains("Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,..."))
        assertTrue(snapshot.contains("번역 Hello"))
        assertEquals(2, snapshot.lines().count { it.startsWith("Dialogue:") })
        translator.clear()
    }
    @Test fun slowTranslationNeverRemovesPendingCuesFromNativeTrack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = Provider { lines -> gate.await(); lines.map { "한국어 $it" } }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider })
        val source = file(srt(40))
        translator.playbackTick(source.path, 0, backgroundScope, "gemini")
        val before = translator.nativeTrackSnapshot()!!
        assertEquals(40, before.lines().count { it.startsWith("Dialogue:") })
        assertTrue(before.contains("Line 39"))
        runCurrent(); advanceTimeBy(20000)
        assertNull(translator.nativeTrackSnapshot()) // No empty replacement during delay.
        gate.complete(Unit); runCurrent()
        val after = translator.nativeTrackSnapshot()!!
        assertEquals(40, after.lines().count { it.startsWith("Dialogue:") })
        assertTrue(after.contains("한국어 Line 0")); assertTrue(after.contains("Line 39"))
        assertEquals(before.lines().filter { it.startsWith("Dialogue:") }.map { it.split(',').take(9) }, after.lines().filter { it.startsWith("Dialogue:") }.map { it.split(',').take(9) })
        translator.clear()
    }
    @Test fun failedTranslationLeavesCompleteNativeTimeline() = runTest {
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider { error("HTTP 401") } }, { false })
        translator.playbackTick(file(srt(8)).path, 0, backgroundScope, "gemini")
        val original = translator.nativeTrackSnapshot()!!
        advanceTimeBy(15000); runCurrent()
        assertEquals(8, original.lines().count { it.startsWith("Dialogue:") })
        assertNull(translator.nativeTrackSnapshot())
        translator.clear()
    }
    @Test fun playbackClockTranslatesFortyConsecutiveCuesWithoutNativeTextEvents() = runTest {
        val provider = Provider()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider })
        val source = file(srt(40))
        for (i in 0 until 40) {
            translator.playbackTick(source.path, i * 2000L + 100, backgroundScope, "gemini")
            advanceTimeBy(200); runCurrent()
            assertTrue("Missing cue $i", translator.consumeReadyTranslatedEvents().any { it.startMs == i * 2000L })
        }
        assertEquals(40, translator.consumeReadyTranslatedEvents().size)
        assertEquals(40, provider.calls)
        translator.clear()
    }
    @Test fun disablingLookAheadStillTranslatesTheCurrentCue() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putBoolean("pref_ai_prefetch_enabled", false).commit()
        val provider = Provider()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider })
        val source = file(srt(4))
        for (i in 0 until 4) {
            translator.playbackTick(source.path, i * 2000L + 100, backgroundScope, "gemini")
            advanceTimeBy(200); runCurrent()
            assertTrue(translator.consumeReadyTranslatedEvents().any { it.startMs == i * 2000L })
        }
        translator.clear()
    }
    @Test fun providerFallbackDoesNotClearCompletedCuesOnTheNextPoll() = runTest {
        val primary = Provider("gemini") { error("HTTP 401 unauthorized") }
        val fallback = Provider("openai")
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { if (it == "gemini") primary else fallback }, { it == "openai" })
        val source = file(srt(4))
        for (i in 0 until 4) {
            translator.playbackTick(source.path, i * 2000L + 100, backgroundScope, "gemini")
            advanceTimeBy(200); runCurrent()
        }
        assertEquals(1, primary.calls)
        assertEquals(4, translator.consumeReadyTranslatedEvents().size)
        translator.clear()
    }
    @Test fun backwardSeekTranslatesPreviouslySkippedCues() = runTest {
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        val source = file(srt(20))
        translator.playbackTick(source.path, 30000, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        translator.playbackTick(source.path, 100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        val starts = translator.consumeReadyTranslatedEvents().map { it.startMs }
        assertTrue(starts.contains(30000L)); assertTrue(starts.contains(0L))
        translator.clear()
    }
    @Test fun lateResultFromPreviousFileCannotContaminateNewSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = Provider { lines -> withContext(NonCancellable) { gate.await() }; lines.map { "OLD" } }
        val fresh = Provider("openai") { lines -> lines.map { "NEW" } }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { if (it == "gemini") old else fresh })
        translator.playbackTick(file(srt(2)).path, 0, backgroundScope, "gemini")
        runCurrent()
        translator.playbackTick(file(srt(3)).path, 0, backgroundScope, "openai")
        advanceTimeBy(200); runCurrent()
        gate.complete(Unit); runCurrent()
        val ready = translator.consumeReadyTranslatedEvents()
        assertEquals(1, ready.size)
        assertTrue(ready.single().assEventData.contains("NEW"))
        assertFalse(ready.single().assEventData.contains("OLD"))
        translator.clear()
    }
    @Test fun nullSourceClearsCompletedEventsAndReentryStartsFresh() = runTest {
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        val source = file(srt(3))
        translator.playbackTick(source.path, 0, backgroundScope, "gemini"); runCurrent()
        assertEquals(1, translator.consumeReadyTranslatedEvents().size)
        assertTrue(translator.playbackTick(null, 0, backgroundScope, "gemini").isEmpty())
        translator.playbackTick(source.path, 4000, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        assertEquals(4000L, translator.consumeReadyTranslatedEvents().single().startMs)
        translator.clear()
    }
    @Test fun mismatchedBatchResponseFallsBackToEachCue() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 4).commit()
        val provider = Provider { lines -> if (lines.size > 1) listOf("잘못된 한 줄") else listOf("개별 번역") }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider }, { false })
        translator.playbackTick(file(srt(5)).path, 0, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        assertEquals(5, translator.consumeReadyTranslatedEvents().size)
        translator.clear()
    }
    @Test fun srtWithMisleadingExtensionPreservesAllCueTimesAndStripsHtml() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 10).commit()
        val provider = Provider { lines -> lines.map { "<font color='red'>안녕</font><br>세계" } }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider })
        translator.playbackTick(file(srt(8), "vtt").path, 0, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        val events = translator.consumeReadyTranslatedEvents()
        assertEquals(8, events.size)
        assertEquals((0 until 8).map { it * 2000L }, events.map { it.startMs })
        assertTrue(events.all { !it.assEventData.contains("<font") && it.assEventData.contains("안녕") })
        translator.clear()
    }
    @Test fun replacingContentsAtTheSamePathInvalidatesOldTranslations() = runTest {
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        val source = file(srt(1))
        translator.playbackTick(source.path, 0, backgroundScope, "gemini"); runCurrent()
        assertEquals(1, translator.consumeReadyTranslatedEvents().size)
        source.writeText(srt(3))
        translator.playbackTick(source.path, 4000, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        val ready = translator.consumeReadyTranslatedEvents()
        assertEquals(1, ready.size)
        assertEquals(4000L, ready.single().startMs)
        translator.clear()
    }

    @Test fun utf16SrtAndAssDialogueBothKeepTheirTimeline() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 4).commit()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        val utf16 = file("").apply { writeBytes(srt(3).toByteArray(Charsets.UTF_16)) }
        translator.playbackTick(utf16.path, 0, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        assertEquals(3, translator.consumeReadyTranslatedEvents().size)
        val ass = file("""[Script Info]
ScriptType: v4.00+
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello
Dialogue: 1,0:00:02.50,0:00:04.00,Default,,0,0,0,,World
""", "vtt")
        translator.playbackTick(ass.path, 0, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        val events = translator.consumeReadyTranslatedEvents()
        assertEquals(listOf(1000L, 2500L), events.map { it.startMs })
        assertEquals(listOf(2000L, 4000L), events.map { it.endMs })
        translator.clear()
    }

}
