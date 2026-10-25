package com.lilac.anime.data.offline

import org.junit.Assert.*
import org.junit.Test

class DownloadQueuePolicyTest {
    @Test fun restartingAppRecoversInterruptedWorkButKeepsUserPauseAndFailure() {
        val states = listOf("downloading", "queued", "paused", "failed", "completed", "cancelled")
        assertEquals(listOf("downloading", "queued"), states.filter(DownloadQueuePolicy::shouldRecover))
    }
    @Test fun retryRequiresAResumableJobAndItsOriginalSource() {
        assertTrue(DownloadQueuePolicy.canResume("failed", "https://example.com/video.m3u8"))
        assertTrue(DownloadQueuePolicy.canResume("paused", "https://example.com/video.mp4"))
        assertFalse(DownloadQueuePolicy.canResume("completed", "https://example.com/video.mp4"))
        assertFalse(DownloadQueuePolicy.canResume("failed", null))
        assertFalse(DownloadQueuePolicy.canResume("cancelled", "https://example.com/video.mp4"))
    }
}
