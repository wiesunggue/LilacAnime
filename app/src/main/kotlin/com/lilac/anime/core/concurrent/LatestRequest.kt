package com.lilac.anime.core.concurrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-thread owner for cancellable UI requests, including non-cooperative providers. */
class LatestRequest(private val scope: CoroutineScope) {
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }

    fun start(block: suspend Ticket.() -> Unit) {
        cancel()
        val ticket = Ticket(generation)
        job = scope.launch { ticket.block() }
    }

    inner class Ticket internal constructor(private val version: Long) {
        val isCurrent: Boolean get() = version == generation

        /** Check after every suspension and before changing UI/player state. */
        suspend fun checkActive() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent) throw CancellationException("Subtitle request superseded")
        }
    }
}
