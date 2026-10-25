package com.lilac.anime.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground-only sync with bounded debounce, periodic pull and retry backoff. */
internal class AutoSyncController(
    private val scope: CoroutineScope,
    private val canSync: () -> Boolean,
    private val synchronize: suspend () -> Unit,
    private val retryable: (Throwable) -> Boolean = { true },
    private val now: () -> Long,
    private val intervalMs: Long = 300_000L,
    private val debounceMs: Long = 2_000L,
    private val minimumIntervalMs: Long = 15_000L,
) {
    private var foreground = false
    private var scheduled: Job? = null
    private var periodic: Job? = null
    private var lastAttempt: Long? = null
    private var failures = 0
    private var followUp = false
    private var running = false

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) { if (!running) { scheduled?.cancel(); scheduled = null }; periodic?.cancel(); periodic = null; return }
        request(immediate = true)
        periodic = scope.launch { while (isActive) { delay(intervalMs); request(immediate = true) } }
    }

    fun request(immediate: Boolean = false) {
        if (!foreground || !canSync()) return
        if (running) { followUp = true; return }
        // Changes keep the first deadline: continuous playback must not postpone
        // synchronization forever by resetting a trailing-edge debounce timer.
        if (scheduled?.isActive == true && !immediate) return
        scheduled?.cancel()
        val remaining = lastAttempt?.let { (minimumIntervalMs - (now() - it)).coerceAtLeast(0L) } ?: 0L
        schedule(if (immediate) remaining else maxOf(debounceMs, remaining))
    }

    private fun schedule(waitMs: Long) {
        scheduled = scope.launch {
            delay(waitMs)
            if (!foreground || !canSync()) return@launch
            running = true; followUp = false; lastAttempt = now()
            var retry: Long? = null
            try { synchronize(); failures = 0 }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (retryable(error)) { failures++; retry = minOf(300_000L, 30_000L * (1L shl minOf(failures - 1, 4))) }
            } finally { running = false; scheduled = null }
            if (foreground && canSync()) {
                if (retry != null) schedule(retry!!) else if (followUp) request()
            }
        }
    }

    fun refresh() { failures = 0; request(immediate = true) }
    fun close() { foreground = false; scheduled?.cancel(); periodic?.cancel() }
}
