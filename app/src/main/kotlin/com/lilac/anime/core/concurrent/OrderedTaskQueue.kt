package com.lilac.anime.core.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Register work before dispatching it, so IO scheduling cannot reorder user edits. */
internal class OrderedTaskQueue(scope: CoroutineScope) {
    private data class Task(val action: suspend () -> Unit, val done: CompletableDeferred<Unit>)
    private val tasks = Channel<Task>(Channel.UNLIMITED, onUndeliveredElement = { task ->
        task.done.cancel(CancellationException("Queue owner stopped before command completed"))
    })
    init {
        scope.launch {
            for (task in tasks) {
                try { task.action(); task.done.complete(Unit) }
                catch (failure: Exception) {
                    task.done.completeExceptionally(failure)
                    coroutineContext.ensureActive()
                }
            }
        }.invokeOnCompletion {
            // This also runs when the owner was cancelled before launch could
            // enter its body. Settle buffered acknowledgements and close input.
            tasks.cancel()
        }
    }
    fun submit(action: suspend () -> Unit): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        if (!tasks.trySend(Task(action, done)).isSuccess) done.completeExceptionally(IllegalStateException("Queue closed"))
        return done
    }
    fun close() { tasks.close() }
}
