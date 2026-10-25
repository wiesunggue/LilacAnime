package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.WatchProgress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceSyncMappingTest {
    @Test fun currentAndroidLinkkfMapsToPcLinkani() {
        assertEquals("linkani:123", DeviceSyncMapping.wireAnimeId("123"))
        assertEquals("123", DeviceSyncMapping.androidAnimeId("linkani:123"))
        assertNull(DeviceSyncMapping.androidAnimeId("linkkf:123"))
        assertEquals("reanime:frieren", DeviceSyncMapping.wireAnimeId("reanime:frieren"))
    }
    @Test fun reanimeUsesPcEpisodeUrlAndPersistsWatchTime() {
        val w = WatchProgress("reanime:frieren", 3, 42f, "reanime:frieren:ep-3", 12345L)
        val local = DeviceSyncMapping.local(setOf(w.animeId), listOf(w), mapOf(w.animeId to Anime(id=w.animeId, title="프리렌")), JSONObject())
        val id = "reanime:https://reanime.to/watch/frieren?ep=3"
        assertEquals(id, DeviceSyncMapping.historyId(w))
        assertEquals(w, DeviceSyncMapping.history(local).single())
        assertEquals("프리렌", local.getJSONObject("library").getJSONObject(w.animeId).getString("title"))
        val second = DeviceSyncMapping.local(setOf(w.animeId), listOf(w), emptyMap(), local)
        assertEquals(12345L, second.getJSONObject("history").getJSONObject(id).getLong("updated"))
    }
    @Test fun importedPcMetadataAndExactEpisodeKeySurviveRoundtrip() {
        val pc = JSONObject("""{"library":{"linkani:12":{"provider":"linkani","mal_id":"linkani:12","id":"12","title":"제목","score":9,"savedAt":123}},"history":{"linkani:https://linkani.tv/watch/12/a1/k4a/":{"key":"linkani:https://linkani.tv/watch/12/a1/k4a/","anime":{"provider":"linkani","id":"12","mal_id":"linkani:12","title":"제목"},"episode":{"number":4,"name":"4a","url":"https://linkani.tv/watch/12/a1/k4a/","provider":"linkani"},"progress":50,"updated":456}}}""")
        val history = DeviceSyncMapping.history(pc)
        assertEquals("12_ep_4a", history.single().episodeKey)
        val roundtrip = DeviceSyncMapping.local(DeviceSyncMapping.library(pc), history, emptyMap(), pc)
        assertEquals(9, roundtrip.getJSONObject("library").getJSONObject("linkani:12").getInt("score"))
        assertEquals(123L, roundtrip.getJSONObject("library").getJSONObject("linkani:12").getLong("savedAt"))
        assertEquals(pc.getJSONObject("history").keys().next(), roundtrip.getJSONObject("history").keys().next())
    }
    @Test fun unsupportedPcProvidersAreRetainedAndDoNotBecomeAndroidFavorites() {
        val previous = JSONObject("""{"library":{"miruro:123":{"mal_id":"miruro:123","title":"PC 전용"}},"history":{}}""")
        val local = DeviceSyncMapping.local(emptySet(), emptyList(), emptyMap(), previous)
        assertTrue(local.getJSONObject("library").has("miruro:123"))
        assertTrue(DeviceSyncMapping.library(local).isEmpty())
    }
    @Test fun canonicalHashIsIndependentOfJsonOrderButTracksRealChangeTimes() {
        assertEquals(DeviceSyncMapping.hash(JSONObject("""{"title":"제목","updated":1,"anime":{"id":"1","title":"작품"}}""")),
            DeviceSyncMapping.hash(JSONObject("""{"anime":{"title":"작품","id":"1"},"updated":1,"title":"제목"}""")))
        assertNotEquals(DeviceSyncMapping.hash(JSONObject("""{"title":"제목","savedAt":1}""")),
            DeviceSyncMapping.hash(JSONObject("""{"title":"제목","savedAt":2}""")))
    }
    @Test fun idsFitPcRulesIncludingPlusAndUtf8Fnv() {
        val encoded = "reanime:" + "%EA%B0%80".repeat(100)
        assertEquals("reanime:" + "가".repeat(100), DeviceSyncMapping.fitId(encoded))
        assertEquals("x:a+b" + "가".repeat(100), DeviceSyncMapping.fitId("x:a+b" + "%EA%B0%80".repeat(100)))
        assertEquals("x:(a)!'*", DeviceSyncMapping.fitId("x:%28a%29%21%27%2A"))
        assertTrue(DeviceSyncMapping.fitId("x:" + "a".repeat(600)).startsWith("x:#"))
    }
    @Test fun readdingAFavoriteHasItsRealChangeTimeAndSparseMetadataKeepsPcFields() {
        val old = JSONObject("""{"library":{"linkani:12":{"mal_id":"linkani:12","title":"제목","title_english":"Title","images":{"webp":{"large_image_url":"poster"}},"savedAt":10}},"history":{}}""")
        val result = DeviceSyncMapping.local(setOf("12"), emptyList(), mapOf("12" to Anime(id="12", title="제목")), old, mapOf("12" to 200L))
        val data = result.getJSONObject("library").getJSONObject("linkani:12")
        assertEquals(200L, data.getLong("savedAt"))
        assertEquals("Title", data.getString("title_english"))
        assertEquals("poster", data.getJSONObject("images").getJSONObject("webp").getString("large_image_url"))
    }
    @Test fun unchangedListsHaveStableHashesAcrossSyncRuns() {
        val w = WatchProgress("reanime:frieren", 1, 10f, "reanime:frieren:ep-1", 100L)
        val first = DeviceSyncMapping.local(setOf(w.animeId), listOf(w), emptyMap(), JSONObject())
        val second = DeviceSyncMapping.local(setOf(w.animeId), listOf(w), emptyMap(), first)
        assertEquals(DeviceSyncMapping.hash(first), DeviceSyncMapping.hash(second))
    }
    @Test fun linkaniFallbackIncludesTheSourceVersionSegment() {
        assertEquals("linkani:https://linkani.tv/watch/123/a1/k4a/",
            DeviceSyncMapping.historyId(WatchProgress("123", 4, 20f, "123_ep_4a")))
    }
    @Test fun tombstonesRemoveTheExactWireKey() {
        val local = DeviceSyncMapping.local(setOf("123"), emptyList(), emptyMap(), JSONObject())
        val result = DeviceSyncMapping.merge(local, listOf(JSONObject("""{"collection":"library","id":"linkani:123","updated":200,"deleted":true}""")))
        assertTrue(DeviceSyncMapping.library(result).isEmpty())
    }
}
