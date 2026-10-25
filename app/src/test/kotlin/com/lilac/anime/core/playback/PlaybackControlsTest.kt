package com.lilac.anime.core.playback

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackControlsTest {
    @Test fun desktopSpeedsPersistWithoutClampingToTwo() {
        assertEquals(3f, PlaybackControls.normalizeSpeed(3f), 0f)
        assertEquals(4f, PlaybackControls.normalizeSpeed(4f), 0f)
        assertEquals(1f, PlaybackControls.normalizeSpeed(Float.NaN), 0f)
        assertEquals(.1f, PlaybackControls.stepSpeed(.1f, -1), 0f)
        assertEquals(4f, PlaybackControls.stepSpeed(4f, 1), 0f)
        assertEquals(3f, PlaybackControls.stepSpeed(2f, 1), 0f)
    }
    @Test fun streamFailureInTheMiddleMustNotMarkEpisodeCompleted() {
        assertFalse(PlaybackControls.completedPosition(50_000L, 100_000L))
        assertFalse(PlaybackControls.completedPosition(0L, 0L))
        assertFalse(PlaybackControls.completedPosition(0L, 1_000L))
        assertTrue(PlaybackControls.completedPosition(99_000L, 100_000L))
        assertTrue(PlaybackControls.completedPosition(1_000L, 1_000L))
    }
    @Test fun percentSeekingRejectsUnknownDuration() {
        assertNull(PlaybackControls.percentPosition(0L, 2))
        assertNull(PlaybackControls.percentPosition(2000L, 10))
        assertEquals(0L, PlaybackControls.percentPosition(2000L, 0))
        assertEquals(1800L, PlaybackControls.percentPosition(2000L, 9))
    }
    @Test fun shortSpaceTapTogglesExactlyOnceDespiteKeyRepeats() = runTest {
        var toggles = 0; val boosts = mutableListOf<Boolean>()
        val control = HoldToBoostController(this, { true }, { toggles++ }, { boosts += it })
        control.down(); control.down(); advanceTimeBy(200); control.up(); control.up()
        advanceUntilIdle()
        assertEquals(1, toggles); assertTrue(boosts.isEmpty())
    }
    @Test fun heldSpaceRestoresBoostWithoutTogglingPlayback() = runTest {
        var toggles = 0; val boosts = mutableListOf<Boolean>()
        val control = HoldToBoostController(this, { true }, { toggles++ }, { boosts += it })
        control.down(); advanceTimeBy(500); control.down(); control.up()
        assertEquals(listOf(true, false), boosts); assertEquals(0, toggles)
    }
    @Test fun cancellationReleasesBoostAndDoesNotToggle() = runTest {
        var toggles = 0; val boosts = mutableListOf<Boolean>()
        val control = HoldToBoostController(this, { true }, { toggles++ }, { boosts += it })
        control.down(); advanceTimeBy(500); control.up(cancelled = true)
        control.down(); advanceTimeBy(100); control.up(cancelled = true); advanceUntilIdle()
        assertEquals(listOf(true, false), boosts); assertEquals(0, toggles)
    }
    @Test fun pausedVideoNeverEntersBoostMode() = runTest {
        var toggles = 0; val boosts = mutableListOf<Boolean>()
        val control = HoldToBoostController(this, { false }, { toggles++ }, { boosts += it })
        control.down(); advanceTimeBy(1000); control.up()
        assertEquals(1, toggles); assertTrue(boosts.isEmpty())
    }
}
