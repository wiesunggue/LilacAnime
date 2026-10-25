package com.lilac.anime.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AutoSyncControllerTest {
    @Test fun startupAndPeriodicPullOnlyRunInForeground() = runTest {
        var calls = 0
        val controller = AutoSyncController(backgroundScope, { true }, { calls++ }, now = { testScheduler.currentTime })
        controller.setForeground(true); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(300_000L); runCurrent(); assertEquals(2, calls)
        controller.setForeground(false); advanceTimeBy(600_000L); runCurrent(); assertEquals(2, calls)
    }
    @Test fun changesDoNotStarveWhilePlaybackKeepsUpdating() = runTest {
        var calls = 0
        val controller = AutoSyncController(backgroundScope, { true }, { calls++ }, now = { testScheduler.currentTime }, minimumIntervalMs = 0L)
        controller.setForeground(true); runCurrent()
        repeat(8) { controller.request(); advanceTimeBy(300L); runCurrent() }
        assertEquals(2, calls)
        controller.close()
    }
    @Test fun offlineStartupWaitsForReadinessAndOptOutBlocksRequests() = runTest {
        var ready = false; var calls = 0
        val controller = AutoSyncController(backgroundScope, { ready }, { calls++ }, now = { testScheduler.currentTime })
        controller.setForeground(true); runCurrent(); assertEquals(0, calls)
        ready = true; controller.refresh(); runCurrent(); assertEquals(1, calls)
        ready = false; controller.request(); advanceTimeBy(300_000L); runCurrent(); assertEquals(1, calls)
        controller.close()
    }
    @Test fun concurrentChangesCoalesceAndProduceOneFollowUp() = runTest {
        var calls = 0; val release = CompletableDeferred<Unit>()
        val controller = AutoSyncController(backgroundScope, { true }, { calls++; if (calls == 1) release.await() }, now = { testScheduler.currentTime })
        controller.setForeground(true); runCurrent(); repeat(20) { controller.request() }; runCurrent(); assertEquals(1, calls)
        release.complete(Unit); runCurrent(); advanceTimeBy(15_000L); runCurrent(); assertEquals(2, calls)
        controller.close()
    }
    @Test fun failuresBackOffAndSuccessfulNetworkRecoveryResetsRetryDelay() = runTest {
        var calls = 0; var failed = true
        val controller = AutoSyncController(backgroundScope, { true }, { calls++; if (failed) throw IOException("offline") }, now = { testScheduler.currentTime })
        controller.setForeground(true); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(29_999L); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1L); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(60_000L); runCurrent(); assertEquals(3, calls)
        failed = false; controller.refresh(); runCurrent(); assertEquals(3, calls)
        advanceTimeBy(15_000L); runCurrent(); assertEquals(4, calls)
        controller.close()
    }
    @Test fun authenticationFailureDoesNotCreateAnAutomaticRetryLoop() = runTest {
        var calls = 0
        val controller = AutoSyncController(backgroundScope, { true }, { calls++; error("reconnect") }, retryable = { false }, now = { testScheduler.currentTime })
        controller.setForeground(true); runCurrent(); advanceTimeBy(120_000L); runCurrent(); assertEquals(1, calls)
        controller.close()
    }
    @Test fun aPendingChangeIsNotSentAfterBackgrounding() = runTest {
        var calls = 0
        val controller = AutoSyncController(backgroundScope, { true }, { calls++ }, now = { testScheduler.currentTime })
        controller.setForeground(true); runCurrent(); controller.request(); controller.setForeground(false)
        advanceTimeBy(20_000L); runCurrent(); assertEquals(1, calls)
        controller.setForeground(true); runCurrent(); assertEquals(2, calls)
        controller.close()
    }
}
