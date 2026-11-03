package com.lilac.anime.player

import com.lilac.anime.data.subtitle.translation.RealtimeSubtitleTranslator.PlaybackSnapshot
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatedSubtitleTrackTest {
    @get:Rule val temp = TemporaryFolder()
    private class Output(var selected: String, var generation: Long = 1L) : TranslatedSubtitleTrack.Output {
        var accepts = true
        var isReady = true
        val attempts = mutableListOf<String>()
        override val selectedPath get() = selected
        override val ready get() = isReady
        override val mediaGeneration get() = generation
        override fun select(path: String): Boolean {
            attempts += path
            if (accepts) selected = path
            return accepts
        }
    }
    @Test fun failedAttachIsRetriedWithoutLosingTheSelectedSourceOrTranslatedSnapshot() = runTest {
        val source = temp.newFile("source.srt").apply { writeText("original") }
        val output = Output(source.path).apply { accepts = false }
        val session = TranslatedSubtitleTrack(temp.root, source.path, output, StandardTestDispatcher(testScheduler))
        session.update(PlaybackSnapshot("translated", "srt"))
        assertEquals(source.path, output.selected)
        val generated = output.attempts.single()
        assertEquals("translated", File(generated).readText())
        output.accepts = true
        session.update(null)
        assertEquals(generated, output.selected)
        assertEquals("original", source.readText())
        session.close()
        assertEquals(source.path, output.selected)
        assertFalse(File(generated).exists())
    }
    @Test fun eachRevisionHasANewCompleteFileAndRetainsTheLastTrackUntilSelectionSucceeds() = runTest {
        val source = temp.newFile("source.vtt").apply { writeText("original") }
        val output = Output(source.path)
        val session = TranslatedSubtitleTrack(temp.root, source.path, output, StandardTestDispatcher(testScheduler))
        session.update(PlaybackSnapshot("first complete timeline", "vtt"))
        val first = output.selected
        output.accepts = false
        session.update(PlaybackSnapshot("second complete timeline", "vtt"))
        assertEquals(first, output.selected)
        assertEquals("first complete timeline", File(first).readText())
        output.accepts = true
        session.update(null)
        assertNotEquals(first, output.selected)
        assertEquals("second complete timeline", File(output.selected).readText())
        assertFalse(File(first).exists())
        session.close()
    }
    @Test fun lateFileLoadedReattachesSnapshotWithoutAnotherTranslationResponse() = runTest {
        val source = temp.newFile("source.ass")
        val output = Output(source.path)
        val session = TranslatedSubtitleTrack(temp.root, source.path, output, StandardTestDispatcher(testScheduler))
        session.update(PlaybackSnapshot("styled translation", "ass"))
        val translated = output.selected
        output.selected = source.path
        session.update(null)
        assertEquals(translated, output.selected)
        session.close()
    }
    @Test fun cleanupCannotOverwriteANewEpisodeOrDeleteATrackStillSelectedAfterRestoreFailure() = runTest {
        val source = temp.newFile("source.srt")
        val output = Output(source.path)
        val session = TranslatedSubtitleTrack(temp.root, source.path, output, StandardTestDispatcher(testScheduler))
        session.update(PlaybackSnapshot("translated", "srt"))
        val translated = output.selected
        output.generation = 2L
        session.close()
        assertEquals(translated, output.selected)
        assertTrue(File(translated).exists())
        assertEquals(1, output.attempts.size)
    }
    @Test fun whileMediaIsLoadingTheOriginalIsNotReplaced() = runTest {
        val source = temp.newFile("source.srt")
        val output = Output(source.path).apply { isReady = false }
        val session = TranslatedSubtitleTrack(temp.root, source.path, output, StandardTestDispatcher(testScheduler))
        session.update(PlaybackSnapshot("translated", "srt"))
        assertEquals(source.path, output.selected)
        output.isReady = true
        session.update(null)
        assertNotEquals(source.path, output.selected)
        session.close()
    }
}
