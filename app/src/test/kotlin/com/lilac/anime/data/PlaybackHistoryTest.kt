package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
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
    @Test fun resumeAdvancesCompletedEpisodeButExplicitReplayDoesNot() {
        val episodes = (1..3).map { Episode("12_ep_$it", it, "${it}화") }
        val finished = WatchProgress("12", 2, .97f, "12_ep_2")
        assertEquals("12_ep_3", PlaybackHistory.selectEpisode(episodes, finished, null)?.id)
        assertEquals("12_ep_2", PlaybackHistory.selectEpisode(episodes, finished, "12_ep_2")?.id)
        assertEquals("12_ep_3", PlaybackHistory.selectEpisode(episodes, finished.copy(episodeKey="12_ep_3", episodeNumber=3), null)?.id)
        assertNull(PlaybackHistory.selectEpisode(episodes, finished, "missing-uuid"))
    }
    @Test fun automaticNextStaysInTheSameDubVersion() {
        val episodes = listOf(Episode("x_ep_1", 1, "1"), Episode("x_dub_ep_1", 1, "1"), Episode("x_dub_ep_2", 2, "2"))
        val latest = WatchProgress("x", 1, 1f, "x_dub_ep_1")
        assertEquals("x_dub_ep_2", PlaybackHistory.selectEpisode(episodes, latest, null)?.id)
        assertEquals(listOf("x_dub_ep_1", "x_dub_ep_2"), PlaybackHistory.orderedEpisodes(episodes, latest.episodeKey).map { it.id })
    }
    @Test fun offlineResumeOnlyOffersDownloadedPlayableEpisodes() {
        val episodes = listOf(Episode("1",1,"1"), Episode("2",2,"2"), Episode("3",3,"방영 예정",playable=false))
        assertEquals(listOf("2"), PlaybackHistory.availableEpisodes(episodes, true) { it.id != "1" }.map { it.id })
        assertEquals(listOf("1","2"), PlaybackHistory.availableEpisodes(episodes, false) { false }.map { it.id })
        assertTrue(PlaybackHistory.availableEpisodes(episodes, true) { false }.isEmpty())
    }
    @Test fun openingSyncedAnimeUsesItsSourceInsteadOfCurrentHomeSource() {
        assertEquals("reanime", PlaybackHistory.sourceFor(Anime(id="reanime:x"), "linkkf"))
        assertEquals("linkkf", PlaybackHistory.sourceFor(Anime(id="123"), "reanime"))
        assertEquals("miruro", PlaybackHistory.sourceFor(Anime(id="miruro:x"), "reanime"))
    }

    @Test fun subAndDubOfSameEpisodeNeverShareProgress() {
        val dubbed = WatchProgress("x", 4, .8f, "x_dub_ep_4")
        val subbed = WatchProgress("x", 4, .2f, "x_ep_4")
        assertNull(PlaybackHistory.find(listOf(dubbed), "x", "x_ep_4", 4))
        assertNull(PlaybackHistory.find(listOf(subbed), "x", "x_dub_ep_4", 4))
    }
    @Test fun implicitResumeSkipsUnreleasedAndUnplayableEpisodes() {
        val episodes = listOf(Episode("x_ep_1",1,"1"), Episode("x_ep_2",2,"미공개",playable=false), Episode("x_ep_3",3,"3"))
        assertEquals("x_ep_3",PlaybackHistory.selectEpisode(episodes,WatchProgress("x",1,1f,"x_ep_1"),null)?.id)
        assertNull(PlaybackHistory.selectEpisode(episodes,null,"x_ep_2"))
    }
}
