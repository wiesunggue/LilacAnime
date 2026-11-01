package com.lilac.anime.core.concurrent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestRequestTest {
    @Test fun cancelStopsSuspendedSearch() = runTest {
        val requests = LatestRequest(backgroundScope)
        var applied = false
        var cancelled = false
        requests.start { try { delay(10000); checkActive(); applied = true } finally { cancelled = true } }
        runCurrent(); requests.cancel(); runCurrent()
        assertTrue(cancelled); assertFalse(applied)
    }
    @Test fun cancelRejectsNonCooperativeLateResult() = runTest {
        val requests = LatestRequest(backgroundScope)
        val response = CompletableDeferred<Unit>()
        var applied = false
        requests.start { withContext(NonCancellable) { response.await() }; checkActive(); applied = true }
        runCurrent(); requests.cancel(); response.complete(Unit); runCurrent()
        assertFalse(applied)
    }
    @Test fun reopenKeepsNewResultAndOldFinallyCannotClearItsLoadingState() = runTest {
        val requests = LatestRequest(backgroundScope)
        val response = CompletableDeferred<Unit>()
        var displayed = ""
        var loading = true
        requests.start {
            try { withContext(NonCancellable) { response.await() }; checkActive(); displayed = "old" }
            finally { if (isCurrent) loading = false }
        }
        runCurrent()
        requests.start { delay(500); checkActive(); displayed = "new"; loading = false }
        runCurrent(); response.complete(Unit); runCurrent()
        assertTrue(loading)
        advanceTimeBy(501); runCurrent()
        assertEquals("new", displayed); assertFalse(loading)
    }
}
