package com.lilac.anime.data.offline

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRetryPolicyTest {
    @Test fun transientHttpAndNetworkFailuresHaveTwoBoundedDelays() {
        assertEquals(30_000L,DownloadRetryPolicy.delayFor(IllegalStateException("playlist HTTP 503: url"),1))
        assertEquals(120_000L,DownloadRetryPolicy.delayFor(SocketTimeoutException(),2))
        assertNull(DownloadRetryPolicy.delayFor(SocketTimeoutException(),3))
    }
    @Test fun authorizationMissingMediaStorageAndCancellationAreNotRetried() {
        for (error in listOf(IllegalStateException("HTTP 403"),IllegalStateException("HTTP 404"),IOException("No space left on device"),CancellationException())) assertNull(DownloadRetryPolicy.delayFor(error,1))
    }
    @Test fun retriesKeepTheSameOperationAndStopOnSuccess() = runTest {
        var calls=0; val waits=mutableListOf<Long>()
        val result=DownloadRetryPolicy.run(onRetry={_,wait->waits+=wait}) { if (++calls<3) throw SocketTimeoutException(); "complete" }
        assertEquals("complete",result);assertEquals(3,calls);assertEquals(listOf(30_000L,120_000L),waits)
        assertEquals(150_000L,testScheduler.currentTime)
    }
    @Test fun cancellationWhileWaitingPreventsRestart() = runTest {
        var calls=0
        val job=launch { DownloadRetryPolicy.run(onRetry={_,_->}) { calls++;throw SocketTimeoutException() } }
        runCurrent();job.cancelAndJoin();advanceUntilIdle()
        assertEquals(1,calls)
    }
    @Test fun persistentFailureStopsAfterThreeAttempts() = runTest {
        var calls=0
        val result=runCatching { DownloadRetryPolicy.run(onRetry={_,_->}) { calls++;throw SocketTimeoutException() } }
        assertTrue(result.isFailure);assertEquals(3,calls)
    }
}
