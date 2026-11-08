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
    @Test fun authenticationFailureDoesNotFanOutIntoPerCueRequestsOrRetryOnSeek() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 40).commit()
        val provider = Provider { error("HTTP 401 invalid key") }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider }, { false })
        val source = file(srt(50))
        translator.playbackTick(source.path, 100, backgroundScope, "gemini")
        advanceTimeBy(60000); runCurrent()
        translator.updatePlaybackPosition(0)
        advanceTimeBy(60000); runCurrent()
        assertEquals("Invalid credentials must stop requests until an explicit retry", 1, provider.calls)
        assertNotNull(translator.playbackProgress().error)
        translator.clear()
    }
    @Test fun networkFailureHasOneBoundedRetryBudgetForTheWholeWindow() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 10).commit()
        val provider = Provider { throw java.io.IOException("offline") }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider }, { false })
        translator.playbackTick(file(srt(12)).path, 100, backgroundScope, "gemini")
        advanceTimeBy(120000); runCurrent()
        assertEquals("Network retries must not multiply by cue count", 3, provider.calls)
        translator.clear()
    }
    @Test fun convertedSubtitleFormatsNeverPublishPendingOriginalDialogueInAiMode() = runTest {
        val sources = mapOf(
            "sbv" to "0:00:00.000,0:00:01.000\nFirst\n\n0:00:02.000,0:00:03.000\nPending",
            "mpl2" to "[0][10]First\n[20][30]Pending",
            "ttml" to "<tt><body><p begin=\"0s\" end=\"1s\">First</p><p begin=\"2s\" end=\"3s\">Pending</p></body></tt>",
        )
        for ((ext, content) in sources) {
            val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider { it.map { "한국어" } } })
            translator.playbackTick(file(content, ext).path, 100, backgroundScope, "gemini"); runCurrent()
            val snapshot = translator.nativePlaybackSnapshot()!!
            assertTrue(snapshot.content.contains("한국어"))
            assertFalse("Pending $ext dialogue must wait for translation", snapshot.content.contains("Pending"))
            assertEquals(2, snapshot.content.lines().count { it.startsWith("Dialogue:") })
            translator.clear()
        }
    }
    @Test fun aiPlaybackCannotReturnToSourceWhenTheNextBatchIsDelayed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = Provider { lines ->
            if (lines.any { it == "Line 1" }) gate.await()
            lines.map { "한국어 ${it.substringAfterLast(' ')}" }
        }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider })
        val source = file(srt(3))
        translator.playbackTick(source.path, 100, backgroundScope, "gemini"); runCurrent()
        val first = translator.nativePlaybackSnapshot()!!
        assertTrue(first.content.contains("한국어 0"))
        assertFalse("AI track must not contain the next untranslated source dialogue", first.content.contains("Line 1"))
        translator.playbackTick(source.path, 2100, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
        assertNull(translator.nativePlaybackSnapshot())
        gate.complete(Unit); runCurrent()
        val next = translator.nativePlaybackSnapshot()!!
        assertTrue(next.content.contains("한국어 0"))
        assertTrue(next.content.contains("한국어 1"))
        translator.clear()
    }
    @Test fun explicitRetryKeepsCompletedCuesAndOnlyRequestsMissingDialogue() = runTest {
        var broken = true
        val requests = mutableListOf<List<String>>()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), {
            Provider { lines ->
                requests += lines
                if (broken && "Line 1" in lines) error("HTTP 401")
                lines.map { "한국어 $it" }
            }
        }, { false })
        val source = file(srt(3))
        translator.playbackTick(source.path, 100, backgroundScope, "gemini"); runCurrent()
        translator.playbackTick(source.path, 2100, backgroundScope, "gemini"); advanceTimeBy(5000); runCurrent()
        assertEquals(1, translator.playbackProgress().completed)
        assertNotNull(translator.playbackProgress().error)
        broken = false
        translator.retryFailedTranslations()
        advanceTimeBy(3000); runCurrent()
        assertEquals(2, translator.playbackProgress().completed)
        assertNull(translator.playbackProgress().error)
        assertEquals(listOf(listOf("Line 0"), listOf("Line 1"), listOf("Line 1")), requests)
        translator.clear()
    }
    @Test fun hidingSubtitlesStopsNewRequestsAndRetainsCompletedWork() = runTest {
        val provider = Provider()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider }, { false })
        val source = file(srt(3))
        translator.playbackTick(source.path, 100, backgroundScope, "gemini"); runCurrent()
        translator.setTranslationEnabled(false)
        translator.playbackTick(source.path, 2100, backgroundScope, "gemini")
        advanceTimeBy(60000); runCurrent()
        assertEquals(1, provider.calls)
        assertEquals(1, translator.playbackProgress().completed)
        translator.setTranslationEnabled(true)
        advanceTimeBy(300); runCurrent()
        assertEquals(2, provider.calls)
        assertEquals(2, translator.playbackProgress().completed)
        translator.clear()
    }
    @Test fun malformedBatchCanSplitButQuotaFailureCannot() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 2).commit()
        for (quota in listOf(false, true)) {
            val provider = Provider { lines ->
                if (quota) error("HTTP 429 quota exhausted")
                if (lines.size > 1) emptyList() else lines.map { "한국어 $it" }
            }
            val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider }, { false })
            translator.playbackTick(file(srt(3)).path, 100, backgroundScope, "gemini")
            advanceTimeBy(60000); runCurrent()
            assertEquals(if (quota) 1 else 4, provider.calls)
            assertEquals(if (quota) 0 else 3, translator.playbackProgress().completed)
            translator.clear()
        }
    }
    @Test fun reserveRequestsTheNextCueBeforeItStartsEvenWithLookAheadOff() = runTest {
        context.getSharedPreferences("lilac_offline_store", 0).edit().putBoolean("pref_ai_prefetch_enabled", false).commit()
        val gate = CompletableDeferred<Unit>()
        val source = file("1\n00:00:00,000 --> 00:00:03,000\nFirst\n\n2\n00:00:03,000 --> 00:00:06,000\nNext")
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), {
            Provider { lines -> if ("Next" in lines) gate.await(); lines.map { "한국어" } }
        })
        translator.playbackTick(source.path, 100, backgroundScope, "gemini"); runCurrent()
        assertTrue(translator.playbackWindowReady(100))
        assertEquals(1, translator.playbackProgress().completed)
        translator.updatePlaybackPosition(2500)
        assertFalse(translator.playbackWindowReady(2500))
        advanceTimeBy(200); runCurrent()
        assertFalse(translator.playbackWindowReady(2500))
        gate.complete(Unit); runCurrent()
        assertTrue(translator.playbackWindowReady(2500))
        assertEquals(2, translator.playbackProgress().completed)
        translator.clear()
    }
    @Test fun overlappingLongSignDoesNotBlockCurrentDialogue() = runTest {
        for (ahead in listOf(0, 10)) {
            context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", ahead).commit()
            val source = file("1\n00:00:00,000 --> 00:01:00,000\nSign\n\n" + (1..29).joinToString("\n\n") { i ->
                "${i+1}\n00:00:%02d,000 --> 00:00:%02d,900\nDialogue $i".format(i, i)
            })
            val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
            translator.playbackTick(source.path, 0, backgroundScope, "gemini")
            advanceTimeBy(200); runCurrent()
            translator.playbackTick(source.path, 22000, backgroundScope, "gemini")
            advanceTimeBy(200); runCurrent()
            val ready = translator.consumeReadyTranslatedEvents()
            assertTrue("Missing current dialogue, ahead=$ahead", ready.any { it.startMs == 22000L })
            assertTrue(ready.any { it.startMs == 0L })
            translator.clear()
        }
    }
    @Test fun shortRewindReconsidersUntranslatedDialogue() = runTest {
        val source = file("1\n00:00:00,000 --> 00:00:00,900\nEarlier\n\n2\n00:00:01,000 --> 00:00:01,900\nLater")
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        translator.playbackTick(source.path, 1100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        translator.playbackTick(source.path, 100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        assertTrue(translator.consumeReadyTranslatedEvents().any { it.startMs == 0L })
        translator.clear()
    }
    @Test fun pausedSeekNearPreviousCueEndTranslatesCurrentCue() = runTest {
        val source = file("1\n00:00:03,000 --> 00:00:03,900\nOld\n\n2\n00:00:04,000 --> 00:00:04,900\nCurrent")
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        translator.playbackTick(source.path, 4100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        assertTrue(translator.consumeReadyTranslatedEvents().any { it.startMs == 4000L })
        assertFalse(translator.consumeReadyTranslatedEvents().any { it.startMs == 3000L })
        translator.clear()
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

    @Test fun validUtf8JapaneseIsNotReinterpretedAsALongerLegacyEncoding() = runTest {
        val japanese = "あ".repeat(60)
        val received = mutableListOf<String>()
        val provider = Provider { lines -> received += lines; lines.map { "번역" } }
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { provider })
        translator.playbackTick(file("1\n00:00:00,000 --> 00:00:02,000\n$japanese").path, 100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(japanese), received)
        translator.clear()
    }

    @Test fun vttDialogueStartingWithNoteIsStillDisplayedAndTranslated() = runTest {
        val received = mutableListOf<String>()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), {
            Provider { lines -> received += lines; lines.map { "번역" } }
        })
        val source = file("WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nNOTE: Listen carefully", "vtt")
        translator.playbackTick(source.path, 100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf("NOTE: Listen carefully"), received)
        translator.clear()
    }

    @Test fun vttCommentTimingsNeverBecomeVisibleSubtitleEvents() = runTest {
        val source = file("WEBVTT\n\nNOTE example timestamps in metadata\n00:00:00.000 --> 00:00:01.000\nDO NOT DISPLAY\n\n00:00:02.000 --> 00:00:03.000\nActual dialogue", "vtt")
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        translator.playbackTick(source.path, 2100, backgroundScope, "gemini")
        advanceTimeBy(200); runCurrent()
        val snapshot = translator.nativeTrackSnapshot()!!
        assertFalse(snapshot.contains("DO NOT DISPLAY"))
        assertEquals(1, snapshot.lines().count { it.startsWith("Dialogue:") })
        translator.clear()
    }

    @Test fun playbackKeepsSrtFormatColoursAndPendingCuesWhileTranslationIsSlow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sourceText = "1\n00:00:00,000 --> 00:00:01,000\n<font color=\"#00ff00\"><b>Hello</b></font>\n\n2\n00:00:02,000 --> 00:00:03,000\n<font color=\"#ff0000\">Later</font>\n"
        val source = file(sourceText)
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), {
            Provider { lines -> gate.await(); lines.map { "안녕" } }
        })
        translator.playbackTick(source.path, 100, backgroundScope, "gemini"); runCurrent()
        assertNull("Translation button must not replace the styled source with an unstyled ASS", translator.nativePlaybackSnapshot())
        gate.complete(Unit); runCurrent()
        val snapshot = translator.nativePlaybackSnapshot()!!
        assertEquals("srt", snapshot.extension)
        assertEquals(sourceText.replace("Hello", "안녕").replace("Later", ""), snapshot.content)
        assertNull(translator.nativePlaybackSnapshot())
        translator.clear()
    }

    @Test fun playbackKeepsVttCssCueIdsSettingsAndSpeakerMarkup() = runTest {
        val sourceText = "WEBVTT\n\nSTYLE\n::cue(.green) { color: lime; }\n\nNOTE preserve this metadata\n\ncue-one\n00:00:00.000 --> 00:00:01.000 line:10% position:25% align:start\n<v Speaker><c.green>Hello</c></v>\n\ncue-two\n00:00:02.000 --> 00:00:03.000\nUntranslated\n"
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider { it.map { "안녕" } } })
        translator.playbackTick(file(sourceText, "vtt").path, 100, backgroundScope, "gemini"); runCurrent()
        val snapshot = translator.nativePlaybackSnapshot()!!
        assertEquals("vtt", snapshot.extension)
        assertEquals(sourceText.replace("Hello", "안녕").replace("Untranslated", ""), snapshot.content)
        translator.clear()
    }

    @Test fun playbackPreservesSamiStylesAndSyncClearEvents() = runTest {
        val sourceText = "<SAMI>\n<HEAD><STYLE>.JPCC { color: yellow; }</STYLE></HEAD>\n<BODY>\n<SYNC Start=0><P Class=JPCC><FONT COLOR=\"red\">Hello</FONT>\n<SYNC Start=1000><P Class=JPCC>&nbsp;\n</BODY></SAMI>"
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider { it.map { "안녕" } } })
        translator.playbackTick(file(sourceText, "smi").path, 100, backgroundScope, "gemini"); runCurrent()
        val snapshot = translator.nativePlaybackSnapshot()!!
        assertEquals("smi", snapshot.extension)
        assertTrue(snapshot.content.contains("<STYLE>.JPCC { color: yellow; }</STYLE>"))
        assertTrue(snapshot.content.contains("<P Class=JPCC><FONT COLOR=\"red\">안녕</FONT>"))
        assertTrue(snapshot.content.contains("<SYNC Start=1000><P Class=JPCC>&nbsp;"))
        translator.clear()
    }

    @Test fun playbackKeepsEarlierTranslationAfterLaterCueCompletesAndRewind() = runTest {
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider() })
        val source = file(srt(12))
        for (i in 0 until 12) {
            translator.playbackTick(source.path, i * 2000L + 100, backgroundScope, "gemini"); advanceTimeBy(200); runCurrent()
            val snapshot = translator.nativePlaybackSnapshot()!!
            assertEquals("srt", snapshot.extension)
            for (translated in 0..i) assertTrue("Previously translated cue $translated disappeared at $i", snapshot.content.contains("번역 Line $translated"))
            assertEquals(12, snapshot.content.lines().count { it.contains(" --> ") })
        }
        translator.playbackTick(source.path, 100, backgroundScope, "gemini"); runCurrent()
        assertEquals(12, translator.playbackProgress().completed)
        assertNull(translator.nativePlaybackSnapshot()) // selected track remains valid during rewind
        translator.clear()
    }

    @Test fun failedProviderKeepsSourceSelectedAndExposesFailureState() = runTest {
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), { Provider { error("HTTP 401") } }, { false })
        translator.playbackTick(file(srt(3)).path, 100, backgroundScope, "gemini"); advanceTimeBy(1000); runCurrent()
        assertNull(translator.nativePlaybackSnapshot())
        assertNotNull(translator.playbackProgress().error)
        assertEquals(0, translator.playbackProgress().completed)
        translator.clear()
    }

    @Test fun playbackPreservesAssDrawingCommandsInsteadOfSendingThemToTheModel() = runTest {
        val drawing = "Dialogue: 0,0:00:00.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 30 0 30 30{\\p0}"
        val sourceText = "[Script Info]\nScriptType: v4.00+\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n$drawing\nDialogue: 0,0:00:00.00,0:00:03.00,Default,,0,0,0,,Hello"
        val received = mutableListOf<String>()
        val translator = RealtimeSubtitleTranslator(context, StandardTestDispatcher(testScheduler), {
            Provider { lines -> received += lines; lines.map { "번역문" } }
        })
        translator.playbackTick(file(sourceText, "ass").path, 100, backgroundScope, "gemini"); runCurrent()
        assertEquals(listOf("Hello"), received)
        val snapshot = translator.nativePlaybackSnapshot()!!
        assertEquals("ass", snapshot.extension)
        assertTrue(snapshot.content.contains(drawing))
        assertTrue(snapshot.content.contains("번역문"))
        translator.clear()
    }

}
