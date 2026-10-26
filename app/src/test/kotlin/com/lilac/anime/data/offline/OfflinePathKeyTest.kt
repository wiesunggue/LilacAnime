package com.lilac.anime.data.offline

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflinePathKeyTest {
    @Test fun longUnicodeAndCaseDistinctIdsNeverCollapseToOneDirectory() {
        val prefix = "animenosub:" + "a".repeat(220)
        assertEquals(OfflinePathKey.legacy(prefix+"_ep_1"),OfflinePathKey.legacy(prefix+"_ep_2"))
        val keys = listOf(prefix+"_ep_1",prefix+"_ep_2","ohli24:작품하나","ohli24:작품둘","x:AbC","x:abc")
        val names=keys.map(OfflinePathKey::fileName)
        assertEquals(keys.size,names.toSet().size)
        assertTrue(names.all { it.length <= 124 && Regex("[a-z0-9._-]+").matches(it) })
    }
    @Test fun existingIdentifiedDownloadRetainsItsPathAndBytes() {
        val root=Files.createTempDirectory("lilac-legacy-").toFile()
        try {
            val anime="anime";val episode="anime_ep_1"
            val old=File(root,OfflinePathKey.legacy("${anime}__${episode}")).apply { mkdirs() }
            File(old,"metadata.json").writeText(JSONObject().put("id","$anime::$episode").toString())
            File(old,"episode.mp4").writeText("original bytes")
            val resolved=OfflinePathKey.directory(root,anime,episode)
            assertEquals(old,resolved);assertEquals("original bytes",File(resolved,"episode.mp4").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun deletingNewCollidingIdCannotDeleteTheExistingOtherEpisode() {
        val root=Files.createTempDirectory("lilac-collision-").toFile()
        try {
            val anime="a".repeat(200);val first="episode1";val second="episode2"
            val old=File(root,OfflinePathKey.legacy("${anime}__${first}")).apply { mkdirs() }
            assertEquals(old,File(root,OfflinePathKey.legacy("${anime}__${second}")))
            File(old,"metadata.json").writeText(JSONObject().put("id","$anime::$first").toString())
            val original=File(old,"episode.mp4").apply { writeText("keep") }
            val other=OfflinePathKey.directory(root,anime,second).apply { mkdirs() }
            assertNotEquals(old,other);other.deleteRecursively()
            assertTrue(original.isFile);assertEquals("keep",original.readText())
        } finally { root.deleteRecursively() }
    }
}
