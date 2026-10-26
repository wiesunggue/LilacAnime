package com.lilac.anime.core.concurrent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedTaskQueueTest {
    @Test fun slowerOldWriteCannotOverwriteNewerEditOrDeletion() = runTest {
        val queue = OrderedTaskQueue(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var disk = "initial"
        val old = queue.submit { release.await(); disk = "old progress" }
        val latest = queue.submit { disk = "new progress" }
        val deleted = queue.submit { disk = "deleted" }
        runCurrent(); assertEquals("initial",disk); assertFalse(deleted.isCompleted)
        release.complete(Unit); old.await();latest.await();deleted.await()
        assertEquals("deleted",disk)
        queue.close()
    }
    @Test fun syncCommitAndLaterLocalEditUseTheSameWriteOrder() = runTest {
        val queue = OrderedTaskQueue(backgroundScope)
        val release = CompletableDeferred<Unit>(); val writes = mutableListOf<String>()
        queue.submit { release.await(); writes += "sync" }
        val edit = queue.submit { writes += "local edit during sync persistence" }
        runCurrent();release.complete(Unit);edit.await()
        assertEquals(listOf("sync","local edit during sync persistence"),writes)
        queue.close()
    }
    @Test fun deleteWaitsForCancelledDownloaderCleanupBeforeReenqueue() = runTest {
        val queue = OrderedTaskQueue(backgroundScope)
        val release = CompletableDeferred<Unit>(); val events = mutableListOf<String>()
        var worker: Job? = null
        queue.submit {
            worker = backgroundScope.launch {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { release.await(); events += "old writer stopped" } }
            }
        }
        runCurrent()
        val deleted = queue.submit { worker!!.cancelAndJoin(); events += "files deleted" }
        val resumed = queue.submit { events += "new writer starts" }
        runCurrent();assertTrue(events.isEmpty()); assertFalse(deleted.isCompleted);assertFalse(resumed.isCompleted)
        release.complete(Unit);resumed.await()
        assertEquals(listOf("old writer stopped","files deleted","new writer starts"),events)
        queue.close()
    }
    @Test fun failedCommandDoesNotDiscardFollowingCommands() = runTest {
        val queue = OrderedTaskQueue(backgroundScope)
        val failed=queue.submit { throw IllegalStateException("failed command") }
        var ran=false;val following=queue.submit { ran=true }
        following.await();assertTrue(ran);assertTrue(failed.isCancelled)
        queue.close()
    }
    @Test fun cancellingAnAwaiterCannotLetAnotherWriteOvertakePendingWork() = runTest {
        val queue = OrderedTaskQueue(backgroundScope);val release=CompletableDeferred<Unit>();val events=mutableListOf<Int>()
        val first=queue.submit { release.await();events+=1 }
        val second=queue.submit { events+=2 };first.cancel();runCurrent();assertTrue(events.isEmpty())
        release.complete(Unit);second.await();assertEquals(listOf(1,2),events)
        queue.close()
    }
}
