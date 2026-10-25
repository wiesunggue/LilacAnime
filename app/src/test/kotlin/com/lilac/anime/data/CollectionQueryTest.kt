package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.WatchProgress
import org.junit.Assert.*
import org.junit.Test

class CollectionQueryTest {
    private val a = Anime(id="reanime:a", title="가나다", english="Alpha", genres=listOf("모험"), year="2024")
    private val b = Anime(id="miruro:b", title="나다라", native="テスト", seasonYear=2025)
    private val c = Anime(id="123", title="다라마", synonyms="Beta", year="2020")
    private val titles = listOf(c, b, a)
    private val history = listOf(WatchProgress(a.id, 1, .2f, updated=10), WatchProgress(a.id, 2, 1f, updated=20), WatchProgress(b.id, 1, .4f, updated=30))
    @Test fun changingSourceRetainsSavedAndHistoryTitlesButDropsUnrelatedCatalog() {
        val updated = a.copy(title="상세 갱신 제목")
        val retained = CollectionQuery.retainCollections(setOf(a.id), listOf(WatchProgress(b.id, 1, .4f)), titles, listOf(updated))
        assertEquals(setOf(a.id, b.id), retained.keys)
        assertEquals(updated, retained[a.id])
        assertFalse(retained.containsKey(c.id))
        assertEquals(listOf(c,b,a), titles)
    }
    @Test fun searchMatchesAlternateTitlesAndAllTerms() {
        assertTrue(CollectionQuery.matches(a,"ALPHA 모험"))
        assertFalse(CollectionQuery.matches(a,"Alpha 없는장르"))
        assertTrue(CollectionQuery.matches(b,"テスト"))
        assertTrue(CollectionQuery.matches(c,"Beta"))
    }
    @Test fun sourceFilterUsesTitleSourceAcrossHomeChanges() {
        assertEquals(listOf(c), CollectionQuery.apply(titles,history,"","linkkf","all","saved"))
        assertEquals(listOf(b), CollectionQuery.apply(titles,history,"","miruro","all","saved"))
    }
    @Test fun watchStateUsesMostRecentEpisodeNotFirstListEntry() {
        assertEquals(listOf(a), CollectionQuery.apply(titles,history,"","all","finished","saved"))
        assertEquals(listOf(b), CollectionQuery.apply(titles,history,"","all","watching","saved"))
        assertEquals(listOf(c), CollectionQuery.apply(titles,history,"","all","new","saved"))
    }
    @Test fun sortsDoNotReorderOriginalSavedList() {
        assertEquals(listOf(a,b,c),CollectionQuery.apply(titles,history,"","all","all","title"))
        assertEquals(listOf(b,a,c),CollectionQuery.apply(titles,history,"","all","all","year"))
        assertEquals(listOf(b,a,c),CollectionQuery.apply(titles,history,"","all","all","recent"))
        assertEquals(listOf(c,b,a),CollectionQuery.apply(titles,history,"","all","all","saved"))
    }
    @Test fun searchFindsEpisodesBeyondTheFirstPage() {
        val episodes = (1..250).map { Episode("ep_$it",it,"회차 $it") }
        assertEquals(listOf(201),CollectionQuery.episodes(episodes,"201",false).map { it.number })
    }
    @Test fun episodeSearchIncludesNativeTitleAndPreservesSpecials() {
        val episodes = listOf(Episode("4a",4,"특별편",displayNumber="4a",nativeTitle="Special"),Episode("4",4,"4화"))
        assertEquals(listOf("4","4a"),CollectionQuery.episodes(episodes,"",false).map { it.id })
        assertEquals(listOf("4a","4"),CollectionQuery.episodes(episodes,"",true).map { it.id })
        assertEquals(listOf("4a"),CollectionQuery.episodes(episodes,"special",false).map { it.id })
    }
}
