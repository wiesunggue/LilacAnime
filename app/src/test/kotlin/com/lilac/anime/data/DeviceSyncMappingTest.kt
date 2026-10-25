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
        val w = WatchProgress("reanime:frieren", 3, .42f, "reanime:frieren:ep-3", 12345L)
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
        val previous = JSONObject("""{"library":{"unknown:123":{"mal_id":"unknown:123","title":"PC 전용"}},"history":{}}""")
        val local = DeviceSyncMapping.local(emptySet(), emptyList(), emptyMap(), previous)
        assertTrue(local.getJSONObject("library").has("unknown:123"))
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
        val w = WatchProgress("reanime:frieren", 1, .10f, "reanime:frieren:ep-1", 100L)
        val first = DeviceSyncMapping.local(setOf(w.animeId), listOf(w), emptyMap(), JSONObject())
        val second = DeviceSyncMapping.local(setOf(w.animeId), listOf(w), emptyMap(), first)
        assertEquals(DeviceSyncMapping.hash(first), DeviceSyncMapping.hash(second))
    }
    @Test fun linkaniFallbackIncludesTheSourceVersionSegment() {
        assertEquals("linkani:https://linkani.tv/watch/123/a1/k4a/",
            DeviceSyncMapping.historyId(WatchProgress("123", 4, .20f, "123_ep_4a")))
    }
    @Test fun progressUsesPcPercentAndAndroidFraction() {
        val w = WatchProgress("reanime:frieren", 3, .42f, "reanime:frieren:ep-3", 100L)
        val local = DeviceSyncMapping.local(emptySet(), listOf(w), emptyMap(), JSONObject())
        val data = local.getJSONObject("history").getJSONObject(DeviceSyncMapping.historyId(w))
        assertEquals(42.0, data.getDouble("progress"), .001)
        assertEquals("percent", data.getString("progressUnit"))
        assertEquals(.42f, DeviceSyncMapping.history(local).single().progress, .0001f)
    }
    @Test fun legacyAndroidProgressIsUpgradedWithoutPretendingItWasWatchedNow() {
        val previous = JSONObject("""{"library":{},"history":{"reanime:https://reanime.to/watch/x?ep=1":{"key":"reanime:https://reanime.to/watch/x?ep=1","androidAnimeId":"reanime:x","androidEpisodeKey":"reanime:x:ep-1","anime":{"mal_id":"reanime:x"},"episode":{"number":1},"progress":0.5,"updated":100}}}""")
        val local = DeviceSyncMapping.local(emptySet(), DeviceSyncMapping.history(previous), emptyMap(), previous)
        val data = local.getJSONObject("history").getJSONObject(local.getJSONObject("history").keys().next())
        assertEquals(50.0, data.getDouble("progress"), .001)
        assertEquals(101L, data.getLong("updated"))
        assertEquals(.5f, DeviceSyncMapping.history(local).single().progress, .0001f)
    }
    @Test fun syncedArtworkIsAvailableWithoutAnEntireCatalogDownload() {
        val data = JSONObject("""{"library":{"reanime:x":{"provider":"reanime","mal_id":"reanime:x","id":"x","title":"작품","images":{"webp":{"large_image_url":"https://example.com/poster.jpg"}},"url":"https://reanime.to/anime/x"}},"history":{}}""")
        val anime = DeviceSyncMapping.animeMetadata(data).getValue("reanime:x")
        assertEquals("작품", anime.title); assertEquals("https://example.com/poster.jpg", anime.poster)
        assertEquals("reanime", anime.source)
    }
    @Test fun allFiveNativeSourcesRetainTheirFavoritesDuringSync() {
        val ids = setOf("123", "reanime:x", "miruro:x", "animenosub:x", "ohli24:x")
        val local = DeviceSyncMapping.local(ids, emptyList(), emptyMap(), JSONObject())
        assertEquals(ids, DeviceSyncMapping.library(local))
        val metadata = DeviceSyncMapping.animeMetadata(local)
        assertEquals("miruro", metadata.getValue("miruro:x").source)
        assertEquals("ohli24", metadata.getValue("ohli24:x").source)
    }
    @Test fun upgradeRestoresPreviouslyHiddenPcFavoritesBeforeDeletionPlanning() {
        val oldWire = JSONObject("""{"library":{"miruro:x":{"mal_id":"miruro:x","title":"PC 보관 작품"}},"history":{"miruro:https://www.miruro.to/watch/x?ep=1":{"key":"miruro:https://www.miruro.to/watch/x?ep=1","anime":{"mal_id":"miruro:x"},"episode":{"number":1},"progress":42,"updated":100}}}""")
        val restored = DeviceSyncMapping.upgradeCollections(setOf("123"), emptyList(), oldWire)
        assertEquals(setOf("123", "miruro:x"), restored.first)
        assertEquals(.42f, restored.second.single().progress, .0001f)
        val outgoing = DeviceSyncMapping.local(restored.first, restored.second, emptyMap(), oldWire)
        assertTrue(outgoing.getJSONObject("library").has("miruro:x"))
        assertTrue(outgoing.getJSONObject("history").has("miruro:https://www.miruro.to/watch/x?ep=1"))
    }
    @Test fun upgradeKeepsNewerLocalProgressAndDoesNotResurrectSupportedDeletions() {
        val oldWire = JSONObject("""{"library":{"linkani:12":{"mal_id":"linkani:12"}},"history":{"miruro:https://www.miruro.to/watch/x?ep=1":{"key":"miruro:https://www.miruro.to/watch/x?ep=1","anime":{"mal_id":"miruro:x"},"episode":{"number":1},"progress":10,"updated":100}}}""")
        val local = WatchProgress("miruro:x", 1, .8f, "miruro:x:1", 200L)
        val restored = DeviceSyncMapping.upgradeCollections(emptySet(), listOf(local), oldWire)
        assertTrue(restored.first.isEmpty())
        assertEquals(local, restored.second.single())
    }
    @Test fun upgradeDoesNotDeleteOlderHiddenHistoryBeyondTheUiLimit() {
        val hashes = JSONObject()
        repeat(600) { hashes.put("miruro:episode-$it", "hash-$it") }
        hashes.put("linkani:deleted-locally", "keep-deletion-detection")
        val before = JSONObject().put("history", hashes).put("library", JSONObject().put("ohli24:x", "old").put("linkani:12", "kept"))
        val migrated = DeviceSyncMapping.upgradeSnapshot(before)
        assertEquals(1, migrated.getJSONObject("history").length())
        assertTrue(migrated.getJSONObject("history").has("linkani:deleted-locally"))
        assertTrue(migrated.getJSONObject("library").has("linkani:12"))
        assertFalse(migrated.getJSONObject("library").has("ohli24:x"))
        assertEquals(601, hashes.length())
    }
    @Test fun tombstonesRemoveTheExactWireKey() {
        val local = DeviceSyncMapping.local(setOf("123"), emptyList(), emptyMap(), JSONObject())
        val result = DeviceSyncMapping.merge(local, listOf(JSONObject("""{"collection":"library","id":"linkani:123","updated":200,"deleted":true}""")))
        assertTrue(DeviceSyncMapping.library(result).isEmpty())
    }
}
