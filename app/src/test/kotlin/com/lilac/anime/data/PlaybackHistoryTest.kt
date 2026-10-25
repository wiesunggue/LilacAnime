package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.WatchProgress
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryTest {
    @Test fun unitConversionRoundtripsAndRepairsOldPersistedPercentages() {
        assertEquals(.42f, PlaybackHistory.fraction(42f), .00001f)
        assertEquals(42.0, PlaybackHistory.percent(.42f), .001)
        assertEquals(.42f, PlaybackHistory.fromWire(42.0, false), .00001f)
        assertEquals(.005f, PlaybackHistory.fromWire(.5, false), .00001f)
        assertEquals(.5f, PlaybackHistory.fromWire(.5, true), .00001f)
    }
    @Test fun invalidOrOutOfRangeProgressCannotSeekOutsideVideo() {
        assertEquals(0f, PlaybackHistory.fraction(Float.NaN), 0f)
        assertEquals(0f, PlaybackHistory.fromWire(Double.POSITIVE_INFINITY, false), 0f)
        assertEquals(1f, PlaybackHistory.fromWire(140.0, false), 0f)
        assertEquals(0L, PlaybackHistory.resumePosition(0L, .5f))
        assertEquals(500L, PlaybackHistory.resumePosition(1_000L, .5f))
        assertEquals(0L, PlaybackHistory.resumePosition(1_000L, .96f))
    }
    @Test fun pcEpisodeIdentityMatchesWhenProviderRegeneratesItsInternalId() {
        val history = listOf(WatchProgress("reanime:x", 3, .4f, "reanime:x:ep-3"))
        assertEquals(.4f, PlaybackHistory.find(history, "reanime:x", "reanime:x:new-uuid", 3)!!.progress, 0f)
        assertNull(PlaybackHistory.find(history, "reanime:y", "reanime:y:ep-3", 3))
    }
    @Test fun episodeFourAndFourADoNotShareProgress() {
        val a = WatchProgress("12", 4, .4f, "12_ep_4a")
        val b = WatchProgress("12", 4, .8f, "12_ep_4")
        assertSame(a, PlaybackHistory.find(listOf(a, b), "12", "4a", 4))
        assertSame(b, PlaybackHistory.find(listOf(a, b), "12", "4", 4))
    }
    @Test fun openingSyncedAnimeUsesItsSourceInsteadOfCurrentHomeSource() {
        assertEquals("reanime", PlaybackHistory.sourceFor(Anime(id="reanime:x"), "linkkf"))
        assertEquals("linkkf", PlaybackHistory.sourceFor(Anime(id="123"), "reanime"))
        assertEquals("miruro", PlaybackHistory.sourceFor(Anime(id="miruro:x"), "reanime"))
    }
}
