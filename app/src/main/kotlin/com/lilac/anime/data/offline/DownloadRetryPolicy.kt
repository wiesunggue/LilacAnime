package com.lilac.anime.data.offline

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Desktop-style bounded retries. Keep partial files; never retry a user cancellation. */
internal object DownloadRetryPolicy {
    private val delays = listOf(30_000L, 120_000L)
    fun delayFor(error: Exception, failures: Int): Long? {
        if (error is CancellationException || failures !in 1..delays.size) return null
        val causes = generateSequence<Throwable>(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
        val http = causes.firstNotNullOfOrNull { Regex("(?i)HTTP\\s+(\\d{3})").find(it.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
        val transient = if (http != null) http in setOf(408, 425, 429, 500, 502, 503, 504)
            else causes.any { it is SocketTimeoutException || it is SocketException || it is UnknownHostException }
        return if (transient) delays[failures - 1] else null
    }
    suspend fun <T> run(onRetry: (Int, Long) -> Unit, action: suspend () -> T): T {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try { return action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val wait = delayFor(error, ++failures) ?: throw error
                onRetry(failures, wait)
                delay(wait)
            }
        }
    }
}
