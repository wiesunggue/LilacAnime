package com.lilac.anime.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleTranslationBufferTest {
    private class Player : SubtitleTranslationBuffer.Output {
        override var generation = 1L
        override var actionRevision = 0L
        override var playing = true
        var pauses = 0
        var resumes = 0
        override fun pause() { playing = false; actionRevision++; pauses++ }
        override fun playIfUnchanged(revision: Long): Boolean {
            if (revision != actionRevision) return false
            playing = true; actionRevision++; resumes++
            return true
        }
    }
    @Test fun delayedNextCuePausesOnceAndResumesOnlyAfterItIsReady() {
        val p = Player(); val buffer = SubtitleTranslationBuffer(p)
        buffer.update(true); buffer.update(true)
        assertFalse(p.playing); assertEquals(1, p.pauses)
        buffer.update(false); buffer.update(false)
        assertTrue(p.playing); assertEquals(1, p.resumes)
    }
    @Test fun userPauseDuringTranslationWaitCancelsAutomaticResume() {
        val p = Player(); val buffer = SubtitleTranslationBuffer(p)
        buffer.update(true); p.pause(); buffer.update(false)
        assertFalse(p.playing); assertEquals(0, p.resumes)
    }
    @Test fun userSeekDuringTranslationWaitCancelsAutomaticResume() {
        val p = Player(); val buffer = SubtitleTranslationBuffer(p)
        buffer.update(true); p.actionRevision++; buffer.update(false)
        assertFalse(p.playing); assertEquals(0, p.resumes)
    }
    @Test fun aNewEpisodeCannotBeResumedByThePreviousBuffer() {
        val p = Player(); val buffer = SubtitleTranslationBuffer(p)
        buffer.update(true); p.generation++; buffer.close()
        assertFalse(p.playing); assertEquals(0, p.resumes)
    }
    @Test fun switchingOffTranslationReleasesOnlyItsOwnPause() {
        val p = Player(); val buffer = SubtitleTranslationBuffer(p)
        buffer.update(true); buffer.close()
        assertTrue(p.playing); assertEquals(1, p.resumes)
        buffer.close(); assertEquals(1, p.resumes)
    }
    @Test fun anAlreadyPausedVideoMustRemainPausedAfterTranslation() {
        val p = Player().apply { playing = false }; val buffer = SubtitleTranslationBuffer(p)
        buffer.update(true); buffer.update(false); buffer.close()
        assertFalse(p.playing); assertEquals(0, p.pauses); assertEquals(0, p.resumes)
    }
}
