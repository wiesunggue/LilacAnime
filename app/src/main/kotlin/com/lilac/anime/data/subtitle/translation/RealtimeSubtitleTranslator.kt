package com.lilac.anime.data.subtitle.translation

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.translation.providers.LocalTranslator
import com.lilac.anime.ui.settings.AiRuntimeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.charset.Charset

class RealtimeSubtitleTranslator(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val providerFactory: (String) -> TranslationProvider = { TranslationManager.createProvider(context, it) },
    private val hasApiKey: (String) -> Boolean = { SecureApiKeyStore.has(context, it) },
) {
    private data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val kind: String,
        val index: Int,
        val bodyStart: Int = -1,
        val bodyEnd: Int = -1,
    )

    private val lock = Mutex()
    private val lifecycleLock = Mutex()
    private var requestedProviderId: String? = null

    // Blocking OkHttp/provider calls may outlive coroutine cancellation. A fresh
    // mutex is assigned to every subtitle session so an old request cannot hold
    // the next episode's translation queue hostage until its network timeout.
    private var translationLock = Mutex()

    private var activeProvider: TranslationProvider = LocalTranslator(context)
    private var activeProviderId: String = "local"
    private var sessionProvider: TranslationSessionProvider? = null
    private var sessionStarted = false
    private val cache = LinkedHashMap<String, String>(256, 0.75f, true)
    private var cues: List<Cue> = emptyList()
    private var sourceContent: String? = null
    private var sourceDialogueBodies: List<List<String>> = emptyList()
    private var sourcePath: String? = null
    private var sourceStamp: Pair<Long, Long>? = null
    private var worker: Job? = null
    private var prefetchPositionMs: Long = 0L
    private var requiredThroughMs: Long = -1L
    private var generation = 0

    data class ReadyEvent(
        val key: String,
        val startMs: Long,
        val endMs: Long,
        val assEventData: String,
    )

    private var cacheRevision = 0L
    private var projectionStamp: Pair<Int, Long>? = null
    private var playbackStamp: Pair<Int, Long>? = null
    private var lastTranslationError: String? = null
    private var retryRequired = false
    private var retryRevision = 0L
    private var translationEnabled = true

    /** Retain completed cues; only failed work needs another paid request. */
    suspend fun retryFailedTranslations() = lock.withLock {
        retryRequired = false
        lastTranslationError = null
        retryRevision++
    }

    suspend fun setTranslationEnabled(enabled: Boolean) = lock.withLock {
        translationEnabled = enabled
    }

    data class PlaybackSnapshot(val content: String, val extension: String)
    data class PlaybackProgress(val completed: Int, val total: Int, val error: String?)

    suspend fun playbackProgress(): PlaybackProgress = lock.withLock {
        PlaybackProgress(cache.size, cues.size, lastTranslationError)
    }

    /** Reserve the current/next second before native playback reaches an
     * untranslated cue. This minimum buffer also works with look-ahead off. */
    suspend fun playbackWindowReady(positionMs: Long): Boolean = lock.withLock {
        val position = positionMs.coerceAtLeast(0L)
        requiredThroughMs = position + 1000L
        cues.none { it.endMs > position && it.startMs <= requiredThroughMs && !cache.containsKey(cueCacheKey(it)) }
    }

    /** Production playback changes dialogue only, never the source container or
     * its styles, cue settings, identifiers, comments and untranslated events. */
    suspend fun nativePlaybackSnapshot(): PlaybackSnapshot? = withContext(dispatcher) { lock.withLock {
        val content = sourceContent ?: return@withLock null
        // Keep the already selected original track until a translation exists.
        if (cache.isEmpty() || cues.isEmpty()) return@withLock null
        val stamp = generation to cacheRevision
        if (playbackStamp == stamp) return@withLock null
        val kind = cues.first().kind
        val result = if (kind in setOf("srt", "vtt", "smi")) {
            var cursor = 0
            buildString {
                for (cue in cues.sortedBy { it.bodyStart }) {
                    val translated = cache[cueCacheKey(cue)]
                    if (cue.bodyStart < cursor || cue.bodyEnd > content.length || cue.bodyStart < 0) continue
                    append(content, cursor, cue.bodyStart)
                    val original = content.substring(cue.bodyStart, cue.bodyEnd)
                    val body = SubtitleMarkup.restoreNativeMarkup(original, translated.orEmpty())
                    append(if (translated == null && kind == "smi") body.replaceFirst(">", ">&nbsp;") else body)
                    cursor = cue.bodyEnd
                }
                append(content, cursor, content.length)
            }
        } else renderAssSnapshot(content, keepPendingOriginal = false)
        playbackStamp = stamp
        PlaybackSnapshot(result, if (kind in setOf("srt", "vtt", "smi")) kind else "ass")
    } }

    // Call only while holding lock. A revision cannot collide like Map.hashCode.
    private fun storeTranslation(cue: Cue, value: String) {
        if (cache.put(cueCacheKey(cue), value) != value) cacheRevision++
        lastTranslationError = null
    }

    /** Full timeline, including original text for pending/failed cues. Like mpv
     * subtrans, publish only changed snapshots and let mpv own rendering/time. */
    suspend fun nativeTrackSnapshot(): String? = withContext(dispatcher) { lock.withLock {
        val content = sourceContent ?: return@withLock null
        if (cues.isEmpty()) return@withLock null
        val stamp = generation to cacheRevision
        if (projectionStamp == stamp) return@withLock null
        val result = renderAssSnapshot(content)
        projectionStamp = stamp
        result
    } }

    /** Legacy ASS projection for overlay callers. PlayerScreen uses nativePlaybackSnapshot. */
    private fun renderAssSnapshot(content: String, keepPendingOriginal: Boolean = true): String {
        val assSource = cues.any { it.kind == "ass" || it.kind == "ssa" }
        val result = if (assSource) {
            // Preserve untranslatable drawings, punctuation, comments and styles.
            // cue.index is the original Dialogue ordinal, not the filtered list index.
            val byOrdinal = cues.associateBy { it.index }
            var ordinal = 0
            content.lineSequence().joinToString("\n") { line ->
                if (!line.trimStart().startsWith("Dialogue:", true)) line else {
                    val cue = byOrdinal[ordinal++]
                    val translated = cue?.let { cache[cueCacheKey(it)] }
                    if (cue == null || translated == null && keepPendingOriginal) line
                    else "Dialogue: " + buildAssEventData(content, cue, restoreTags(cue.text, translated.orEmpty(), cue.kind))
                }
            }
        } else buildString {
            append(NATIVE_HEADER.trimEnd()); append('\n')
            for (cue in cues) {
                val translated = cache[cueCacheKey(cue)]
                val text = if (translated == null) {
                    if (keepPendingOriginal) SubtitleMarkup.plainText(cue.text) else ""
                } else restoreTags(cue.text, translated, cue.kind)
                append("Dialogue: "); append(buildAssEventData(content, cue, text)); append('\n')
            }
        }
        return result
    }

    private suspend fun prepare(
        path: String?,
        positionMs: Long,
        scope: CoroutineScope,
        providerId: String = "local",
    ) {
        val requestedPath = path?.removePrefix("file://")
        val enginePath: String? = null
        val resolvedPath = requestedPath?.let(::File)?.takeIf { it.isFile }?.absolutePath

        val file = resolvedPath?.let(::File)
        if (file == null) {
            Log.w(
                TAG,
                "SESSION_PREPARE_NO_FILE provider=$providerId requested=$requestedPath engine=$enginePath; clearing stale subtitle session",
            )
            clearSession()
            return
        }

        val content = withContext(dispatcher) {
            readSubtitleText(file)?.replace("\r\n", "\n")?.replace('\r', '\n')
        }
        if (content == null) {
            Log.e(TAG, "SESSION_READ_FAILED path=${file.absolutePath}")
            clearSession()
            return
        }

        val dialogueBodies = withContext(dispatcher) {
            content.lineSequence().filter { it.trimStart().startsWith("Dialogue:", true) }
                .map { it.substringAfter(':').trimStart().split(',', limit = 10) }.toList()
        }
        val parsed = withContext(dispatcher) { runCatching { parse(content, file.extension.lowercase()) }
            .onFailure { Log.e(TAG, "PARSE_FAILED path=${file.absolutePath}", it) }
            .getOrDefault(emptyList()) }
        currentCoroutineContext().ensureActive()
        if (parsed.isEmpty()) {
            Log.w(
                TAG,
                "PARSE_EMPTY path=${file.absolutePath} ext=${file.extension} requested=$requestedPath engine=$enginePath",
            )
        }


        lock.withLock {
            generation++
            worker?.cancel()
            worker = null
            translationLock = Mutex()
            cache.clear()
            lastTranslationError = null
            retryRequired = false
            sourceContent = content
            sourceDialogueBodies = dialogueBodies
            sourcePath = file.absolutePath
            sourceStamp = file.lastModified() to file.length()
            cues = parsed
            prefetchPositionMs = positionMs.coerceAtLeast(0L)
            requiredThroughMs = -1L
        }

        endProviderSessionQuietly()

        activeProviderId = providerId.trim().lowercase().ifBlank { "local" }
        requestedProviderId = activeProviderId
        activeProvider = providerFactory(activeProviderId)
        sessionProvider = activeProvider as? TranslationSessionProvider
        val localGeneration = lock.withLock { generation }

        Log.i(
            TAG,
            "SESSION_PREPARE provider=$activeProviderId cues=${parsed.size} file=${file.name} requested=$requestedPath engine=$enginePath positionMs=$positionMs",
        )

        worker = scope.launch(dispatcher) {
            prefetchLoop(localGeneration)
        }
    }

    /**
     * Idempotently prepares the translator for the subtitle that mpv is actually
     * using. This is intentionally safe to call from the PlayerScreen polling
     * loop: it only rebuilds the session when the source/provider really changed.
     */
    suspend fun ensurePrepared(
        path: String?,
        positionMs: Long,
        scope: CoroutineScope,
        providerId: String = "local",
    ) = lifecycleLock.withLock {
        val normalized = path
            ?.removePrefix("file://")
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.absolutePath
            ?: run {
                if (sourcePath != null) clearSession()
                return@withLock
            }
        val currentFile = File(normalized)
        val stamp = currentFile.lastModified() to currentFile.length()
        val wantedProvider = providerId.trim().lowercase().ifBlank { "local" }
        val alreadyPrepared = lock.withLock {
            sourcePath == normalized && sourceStamp == stamp &&
                requestedProviderId == wantedProvider &&
                sourceContent != null && worker?.isActive == true
        }
        if (!alreadyPrepared) {
            Log.i(TAG, "SESSION_ENSURE_REPREPARE provider=$wantedProvider path=$normalized positionMs=$positionMs")
            prepare(normalized, positionMs, scope, wantedProvider)
        }
    }

    suspend fun playbackTick(path: String?, positionMs: Long, scope: CoroutineScope, providerId: String): List<ReadyEvent> {
        ensurePrepared(path, positionMs, scope, providerId)
        updatePlaybackPosition(positionMs)
        return consumeReadyTranslatedEvents()
    }

    /** Playback-time lookup only. Translation runs only in the prefetch worker. */
    suspend fun updatePlaybackPosition(positionMs: Long) {
        lock.withLock { prefetchPositionMs = positionMs.coerceAtLeast(0L) }
    }

    suspend fun clear() = lifecycleLock.withLock { clearSession() }

    private suspend fun clearSession() {
        val oldWorker = lock.withLock {
            generation++
            val old = worker
            worker = null
            translationLock = Mutex()
            cache.clear()
            lastTranslationError = null
            retryRequired = false
            cues = emptyList()
            sourceContent = null
            sourceDialogueBodies = emptyList()
            sourcePath = null
            sourceStamp = null
            prefetchPositionMs = 0L
            requiredThroughMs = -1L
            old
        }
        oldWorker?.cancel()
        endProviderSessionQuietly()
        activeProviderId = "local"
        activeProvider = LocalTranslator(context)
        sessionProvider = null
        requestedProviderId = null
    }

    private suspend fun endProviderSessionQuietly() {
        if (!sessionStarted) return
        runCatching { sessionProvider?.endSession() }
            .onFailure { Log.w(TAG, "SESSION_END_FAILED", it) }
        sessionStarted = false
    }

    private suspend fun ensureSession() {
        if (sessionStarted) return
        val provider = sessionProvider ?: return
        provider.beginSession()
        currentCoroutineContext().ensureActive()
        sessionStarted = true
    }

    private suspend fun prefetchLoop(localGeneration: Int) {
        val failures = mutableMapOf<String, Int>()
        var seenRetry = lock.withLock { retryRevision }

        while (true) {
            currentCoroutineContext().ensureActive()
            if (localGeneration != lock.withLock { generation }) return
            val retry = lock.withLock { retryRevision }
            if (retry != seenRetry) { failures.clear(); seenRetry = retry }
            if (lock.withLock { retryRequired || !translationEnabled }) { delay(150L); continue }
            val snapshot = lock.withLock { cues }
            if (snapshot.isEmpty()) {
                delay(250L)
                if (localGeneration != lock.withLock { generation }) return
                continue
            }

            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            // Disabling look-ahead must still translate the current cue.
            val prefetchAhead = if (prefs.getBoolean("pref_ai_prefetch_enabled", true)) {
                prefs.getInt("pref_ai_prefetch_ahead", 10).coerceIn(0, 40)
            } else 0
            val contextCueCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 6)
            val batchSize = prefs.getInt("pref_ai_batch_size", 5).coerceIn(2, 8)
            val position = lock.withLock { prefetchPositionMs }
            val required = lock.withLock { requiredThroughMs }
            val ordered = snapshot.sortedWith(
                compareBy<Cue> { it.startMs }.thenBy { it.endMs }.thenBy { it.index },
            )

            // Re-evaluate all active cues on every tick. Long overlapping signs
            // must not anchor the future window, and every rewind must recover
            // uncached dialogue regardless of seek distance.
            val active = ordered.filter { it.startMs <= position && it.endMs > position }
            val upcoming = ordered.filter { it.startMs > position }
                .take(prefetchAhead + if (active.isEmpty()) 1 else 0)
            val capacity = if (activeProvider is LocalTranslator) 1 else batchSize
            val urgent = ordered.filter { it.startMs > position && it.startMs <= required }
            val batch = (active + urgent + upcoming).distinctBy { it.index }.filter { cue ->
                lock.withLock { !cache.containsKey(cueCacheKey(cue)) } && (failures[cueCacheKey(cue)] ?: 0) < 3
            }.take(capacity)
            if (batch.isEmpty()) { delay(150L); continue }
            val selectedProvider = activeProvider
            if (localGeneration != lock.withLock { generation }) return

            Log.i(
                TAG,
                "PREFETCH_BATCH_BEGIN provider=$activeProviderId size=${batch.size} firstCue=${batch.first().index} lastCue=${batch.last().index} position=$position",
            )

            try {
                val sessionMutex = translationLock
                sessionMutex.withLock {
                    ensureSession()
                    if (localGeneration != lock.withLock { generation }) {
                        throw CancellationException("subtitle session changed")
                    }

                    if (selectedProvider is LocalTranslator) {
                        translateLocalCue(
                            provider = selectedProvider,
                            cue = batch.first(),
                            ordered = ordered,
                            contextCueCount = contextCueCount,
                            localGeneration = localGeneration,
                        )
                    } else {
                        translateCloudCues(
                            selectedProvider = selectedProvider,
                            batch = batch,
                            localGeneration = localGeneration,
                        )
                    }
                }
            } catch (error: CancellationException) {
                Log.d(TAG, "PREFETCH_CANCELLED provider=$activeProviderId")
                throw error
            } catch (error: Throwable) {
                lock.withLock { if (localGeneration == generation) lastTranslationError = error.message ?: "Translation failed" }
                Log.e(
                    TAG,
                    "PREFETCH_BATCH_FAILED provider=$activeProviderId cues=${batch.joinToString { it.index.toString() }}",
                    error,
                )
                // Cloud requests already have a bounded retry budget. Do not
                // repeat that budget for every cue or restart it after a seek.
                var exhausted = selectedProvider !is LocalTranslator
                batch.forEach { cue ->
                    val key = cueCacheKey(cue)
                    val attempt = (failures[key] ?: 0) + 1
                    failures[key] = attempt
                    if (attempt >= 3) {
                        exhausted = true
                        Log.e(TAG, "PREFETCH_GIVE_UP provider=$activeProviderId cue=${cue.index} attempts=$attempt")
                    }
                }
                if (exhausted) {
                    lock.withLock { if (localGeneration == generation) retryRequired = true }
                    AiRuntimeDiagnostics.record(context, "실시간 자막 번역 ($activeProviderId)", error)
                }
                delay(if (activeProviderId == "local") 500L else 2000L)
            }
        }
    }

    private suspend fun translateLocalCue(
        provider: LocalTranslator,
        cue: Cue,
        ordered: List<Cue>,
        contextCueCount: Int,
        localGeneration: Int,
    ) {
        val orderedIndex = ordered.indexOf(cue)
        val contextStart = (orderedIndex - contextCueCount).coerceAtLeast(0)
        val contextCues = ordered.subList(contextStart, orderedIndex)
        val contextMemory = contextCues.map { previous ->
            LocalTranslator.LocalAiContext(
                source = modelText(previous.text, previous.kind),
                translation = lock.withLock { cache[cueCacheKey(previous)] },
            )
        }
        val futureLines = ordered
            .subList(orderedIndex + 1, (orderedIndex + 3).coerceAtMost(ordered.size))
            .map { modelText(it.text, it.kind) }
        val source = modelText(cue.text, cue.kind)
        Log.i(TAG, "PREFETCH_TRANSLATE provider=$activeProviderId cue=${cue.index} contextCount=${contextMemory.size}")
        val result = provider.translateWithContext(source, contextMemory, futureLines).trim()
        if (result.isBlank()) error("번역 결과가 비어 있습니다.")
        lock.withLock {
            if (localGeneration == generation) storeTranslation(cue, result)
        }
    }

    private suspend fun translateCloudCues(
        selectedProvider: TranslationProvider,
        batch: List<Cue>,
        localGeneration: Int,
    ) {
        val sources = batch.map { modelText(it.text, it.kind) }
        Log.i(
            TAG,
            "PREFETCH_TRANSLATE_BATCH provider=$activeProviderId model=${batch.size} cues=${batch.joinToString { it.index.toString() }}",
        )

        try {
            val results = translateCloudBatchWithFallback(selectedProvider, sources, localGeneration)
            if (results.size != batch.size || results.any { it.isBlank() }) {
                error("번역 응답 줄 수/내용 불일치 expected=${batch.size} actual=${results.size}")
            }
            lock.withLock {
                if (localGeneration == generation) {
                    batch.indices.forEach { index ->
                        storeTranslation(batch[index], results[index].trim())
                    }
                }
            }
            Log.i(TAG, "PREFETCH_TRANSLATE_BATCH_OK provider=$activeProviderId count=${results.size}")
        } catch (batchError: Throwable) {
            if (batchError is CancellationException) throw batchError
            // Smaller requests can repair malformed model output, never an
            // invalid key, exhausted quota or disconnected network.
            if (batch.size <= 1 || batchError !is TranslationResponseException) throw batchError
            Log.w(
                TAG,
                "PREFETCH_BATCH_FALLBACK provider=$activeProviderId count=${batch.size} reason=${batchError.message}",
                batchError,
            )

            // A batch-format/model-limit failure should not discard the whole
            // prefetch window. Retry each cue individually.
            for (cue in batch) {
                if (localGeneration != lock.withLock { generation }) {
                    throw CancellationException("subtitle session changed")
                }
                if (lock.withLock { !translationEnabled }) return
                val single = translateCloudBatchWithFallback(
                    selectedProvider,
                    listOf(modelText(cue.text, cue.kind)),
                    localGeneration,
                ).single().trim()
                lock.withLock {
                    if (localGeneration == generation) storeTranslation(cue, single)
                }
                Log.i(TAG, "PREFETCH_SINGLE_FALLBACK_OK provider=$activeProviderId cue=${cue.index}")
            }
        }
    }

    private suspend fun translateCloudBatchWithFallback(
        primaryProvider: TranslationProvider,
        lines: List<String>,
        localGeneration: Int,
    ): List<String> {
        val primaryId = primaryProvider.id.lowercase()
        val providerIds = (
            listOf(primaryId) +
                listOf("gemini", "openai", "deepl", "qwen").filter { it != primaryId }
            ).distinct()
        val failures = mutableListOf<String>()
        var lastError: Throwable? = null
        var onlyMalformedResponses = true

        for (providerId in providerIds) {
            if (providerId != primaryId && !hasApiKey(providerId)) continue
            val provider = if (providerId == primaryId) {
                primaryProvider
            } else {
                providerFactory(providerId)
            }

            for (attempt in 0..2) {
                try {
                    while (lock.withLock { !translationEnabled }) {
                        delay(150L)
                        if (localGeneration != lock.withLock { generation }) throw CancellationException("Subtitle changed")
                    }
                    Log.i(
                        TAG,
                        "CLOUD_PROVIDER_ATTEMPT provider=$providerId attempt=${attempt + 1}/3 lines=${lines.size}",
                    )
                    currentCoroutineContext().ensureActive()
                    val results = provider.translateBatch(lines)
                    currentCoroutineContext().ensureActive()
                    if (localGeneration != lock.withLock { generation }) throw CancellationException("Subtitle changed")
                    if (results.size != lines.size || results.any { it.isBlank() }) {
                        throw TranslationResponseException("$providerId 응답 줄 수/내용 불일치 expected=${lines.size} actual=${results.size}")
                    }
                    if (providerId != primaryId) {
                        Log.w(
                            TAG,
                            "CLOUD_PROVIDER_FALLBACK_SUCCESS requested=$primaryId used=$providerId lines=${lines.size}",
                        )
                        activeProvider = provider
                        activeProviderId = providerId
                        sessionProvider = provider as? TranslationSessionProvider
                        sessionStarted = false
                    } else {
                        Log.i(TAG, "CLOUD_PROVIDER_SUCCESS provider=$providerId lines=${lines.size}")
                    }
                    return results
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error !is TranslationResponseException) onlyMalformedResponses = false
                    lastError = error
                    failures += "$providerId: ${error.message ?: error.javaClass.simpleName}"
                    val message = error.message.orEmpty()
                    val quotaExhausted = Regex(
                        "quota|billing|insufficient|daily limit|free usage|사용량을.*(초과|소진)|한도",
                        RegexOption.IGNORE_CASE,
                    ).containsMatchIn(message)
                    val httpCode = Regex("HTTP\\s+(\\d{3})")
                        .find(message)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    val transient = error is java.io.IOException ||
                        (httpCode != null && (httpCode >= 500 || httpCode == 429 && !quotaExhausted))
                    Log.w(
                        TAG,
                        "CLOUD_PROVIDER_FAILED provider=$providerId attempt=${attempt + 1} transient=$transient message=$message",
                        error,
                    )
                    if (!transient || attempt == 2) break
                    delay((1500L shl attempt).coerceAtMost(6000L))
                }
            }
            Log.w(TAG, "CLOUD_PROVIDER_EXHAUSTED provider=$providerId")
        }

        val summary = failures.distinct().joinToString(" | ").take(2500)
        if (onlyMalformedResponses && failures.isNotEmpty()) throw TranslationResponseException(summary)
        throw IllegalStateException(
            if (summary.isBlank()) {
                "사용 가능한 클라우드 번역 API 키가 없습니다. AI 설정에서 API 키를 저장하세요."
            } else {
                "설정된 클라우드 번역 API가 모두 실패했습니다. $summary"
            },
            lastError,
        )
    }

    private fun cueCacheKey(cue: Cue): String = "cue:${cue.kind}:${cue.index}"

    /**
     * Returns all currently completed events. This is intentionally non-destructive.
     * LibassTranslationOverlay performs event deduplication. Therefore an event
     * cannot be lost merely because AndroidView/libass was not ready during one
     * polling iteration.
     */
    suspend fun consumeReadyTranslatedEvents(): List<ReadyEvent> = lock.withLock {
        if (cues.isEmpty() || cache.isEmpty()) return@withLock emptyList()
        val content = sourceContent ?: return@withLock emptyList()
        val result = ArrayList<ReadyEvent>()
        for (cue in cues.sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.index })) {
            val key = cueCacheKey(cue)
            val translated = cache[key] ?: continue
            val text = restoreTags(cue.text, translated, cue.kind)
            val eventData = buildAssEventData(content, cue, text)
            result += ReadyEvent(key, cue.startMs, cue.endMs, eventData)
        }
        result
    }

    private fun buildAssEventData(content: String, cue: Cue, translated: String): String {
        if (cue.kind != "ass" && cue.kind != "ssa") {
            return listOf(
                "0",
                assTime(cue.startMs),
                assTime(cue.endMs),
                "Default",
                "",
                "0", "0", "0", "",
                translated.replace("\n", "\\N"),
            ).joinToString(",")
        }

        val body = sourceDialogueBodies.getOrNull(cue.index)

        if (body == null || body.size < 10) {
            return listOf(
                "0", assTime(cue.startMs), assTime(cue.endMs), "Default", "",
                "0", "0", "0", "", translated.replace("\n", "\\N"),
            ).joinToString(",")
        }

        return listOf(
            body[0], body[1], body[2], body[3], body[4],
            body[5], body[6], body[7], body[8],
            translated.replace("\n", "\\N"),
        ).joinToString(",")
    }

    private fun modelText(text: String, kind: String): String =
        SubtitleMarkup.plainText(text).replace("\n", "\\N")

    private fun sanitizeTranslatedText(value: String): String = SubtitleMarkup.plainText(value)

    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = sanitizeTranslatedText(translated)
        val isAss = kind == "ass" || kind == "ssa"
        if (!isAss) {
            return normalized.replace("\\N", "\n").replace("\n", "\\N")
        }

        return SubtitleMarkup.restoreAssMarkup(original, translated)
    }

    private fun parse(text: String, ext: String): List<Cue> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

        // Strong content signatures win over a cached/downloaded file extension.
        // This fixes old files saved as .vtt even though their body is SRT/ASS.
        val sniffed = when {
            normalized.trimStart().startsWith("WEBVTT", true) -> "vtt"
            Regex("(?im)^\\s*Dialogue:").containsMatchIn(normalized) -> "ass"
            Regex("(?is)<SYNC\\s+Start\\s*=").containsMatchIn(normalized) -> "smi"
            Regex("(?is)<tt[ >]|<ttml").containsMatchIn(normalized) -> "ttml"
            Regex("(?m)^\\s*\\[\\d+\\]\\s*\\[\\d+\\]").containsMatchIn(normalized) -> "sub"
            Regex("(?m)^\\s*(?:\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*(?:\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(normalized) -> "srt"
            else -> ""
        }
        val detected = sniffed.ifBlank { ext.lowercase() }

        Log.d(TAG, "PARSE_FORMAT path=$sourcePath ext=$ext detected=$detected")
        return when (detected) {
            "ass", "ssa" -> parseAss(normalized)
            "srt" -> parseTimedText(normalized, "srt")
            "vtt" -> parseTimedText(normalized, "vtt")
            "smi", "sami" -> parseSmi(normalized)
            "sbv" -> parseSbv(normalized)
            "sub", "mpl2" -> parseSubLike(normalized, detected)
            "ttml", "xml" -> parseTtml(normalized)
            else -> emptyList()
        }
    }

    private fun parseTimedText(text: String, kind: String): List<Cue> {
        val lines = text.lines()
        val offsets = IntArray(lines.size)
        for (line in 1 until lines.size) offsets[line] = offsets[line - 1] + lines[line - 1].length + 1
        val result = mutableListOf<Cue>()
        var i = 0
        var index = 0
        val timing = Regex("^\\s*([^\\s]+)\\s*-->\\s*([^\\s]+)(?:\\s+.*)?$")

        while (i < lines.size) {
            // WebVTT comments/style/region blocks are metadata only outside a
            // cue. Their contents can include example timestamps. Conversely,
            // NOTE inside a timed cue is ordinary dialogue and must survive.
            val header = lines[i].trim()
            if (kind == "vtt" && (header == "NOTE" || header.startsWith("NOTE ") ||
                    header.startsWith("NOTE\t") || header == "STYLE" || header == "REGION")) {
                i++
                while (i < lines.size && lines[i].isNotBlank()) i++
                continue
            }
            val match = timing.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val start = parseTime(match.groupValues[1])
            val end = parseTime(match.groupValues[2])
            if (start < 0L || end <= start) {
                i++
                continue
            }

            val bodyStart = i + 1
            var bodyEnd = bodyStart
            while (bodyEnd < lines.size && lines[bodyEnd].isNotBlank() && !timing.matches(lines[bodyEnd])) bodyEnd++
            val body = lines.subList(bodyStart, bodyEnd).joinToString("\\N").trim()

            if (body.isNotBlank()) result += Cue(start, end, body, kind, index++, offsets[bodyStart],
                if (bodyEnd < lines.size) offsets[bodyEnd] - 1 else text.length)
            i++
            while (i < lines.size && !timing.matches(lines[i]) && lines[i].isNotBlank()) i++
        }
        return result
    }

    private fun parseAss(text: String): List<Cue> {
        val result = mutableListOf<Cue>()
        val visibleLetter = Regex("\\p{L}")
        var ordinal = 0

        text.lines().forEach { line ->
            if (!line.trimStart().startsWith("Dialogue:", true)) return@forEach
            val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
            if (parts.size < 10) {
                ordinal++
                return@forEach
            }
            val start = parseAssTime(parts[1])
            val end = parseAssTime(parts[2])
            val body = parts[9].trim()
            val visible = stripSubtitleMarkup(body)
            if (start >= 0L && end > start && visibleLetter.containsMatchIn(visible) && !SubtitleMarkup.isAssDrawing(body)) {
                result += Cue(start, end, body, "ass", ordinal)
            }
            ordinal++
        }
        return result
    }

    private fun parseSmi(text: String): List<Cue> {
        val matches = Regex(
            "(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)",
        ).findAll(text).toList()
        return matches.mapIndexedNotNull { index, match ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val next = matches.getOrNull(index + 1)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: start + 4000L
            val body = match.groupValues[2].trim()
            val range = match.groups[2]!!.range
            if (SubtitleMarkup.plainText(body).isBlank() || next <= start) null
            else Cue(start, next, body, "smi", index, range.first, range.last + 1)
        }
    }

    private fun parseSbv(text: String): List<Cue> {
        val blocks = text.split(Regex("\n\\s*\n+"))
        return blocks.mapIndexedNotNull { index, block ->
            val lines = block.lines()
            val first = lines.firstOrNull()?.trim() ?: return@mapIndexedNotNull null
            val pair = first.split(',', limit = 2)
            if (pair.size != 2) return@mapIndexedNotNull null
            val start = parseTime(pair[0])
            val end = parseTime(pair[1])
            val body = lines.drop(1).joinToString("\\N").trim()
            if (start >= 0L && end > start && body.isNotBlank()) {
                Cue(start, end, body, "sbv", index)
            } else null
        }
    }

    private fun parseSubLike(text: String, kind: String): List<Cue> =
        text.lineSequence().mapIndexedNotNull { index, line ->
            val match = Regex("^\\s*\\[(\\d+)\\]\\s*\\[(\\d+)\\]\\s*(.*)$")
                .find(line)
                ?: return@mapIndexedNotNull null
            val start = match.groupValues[1].toLongOrNull()?.times(100L)
                ?: return@mapIndexedNotNull null
            val end = match.groupValues[2].toLongOrNull()?.times(100L)
                ?: return@mapIndexedNotNull null
            val body = match.groupValues[3].replace("|", "\\N").trim()
            if (end <= start || body.isBlank()) null else Cue(start, end, body, kind, index)
        }.toList()

    private fun parseTtml(text: String): List<Cue> {
        val regex = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
        return regex.findAll(text).mapIndexedNotNull { index, match ->
            val attrs = match.groupValues[1]
            val body = stripSubtitleMarkup(match.groupValues[2]).trim()
            val begin = Regex("(?i)\\bbegin\\s*=\\s*[\"']([^\"']+)")
                .find(attrs)?.groupValues?.get(1)
            val end = Regex("(?i)\\bend\\s*=\\s*[\"']([^\"']+)")
                .find(attrs)?.groupValues?.get(1)
            val dur = Regex("(?i)\\bdur\\s*=\\s*[\"']([^\"']+)")
                .find(attrs)?.groupValues?.get(1)
            val start = parseTtmlTime(begin) ?: return@mapIndexedNotNull null
            val finish = parseTtmlTime(end)
                ?: dur?.let { start + (parseTtmlTime(it) ?: return@mapIndexedNotNull null) }
                ?: return@mapIndexedNotNull null
            if (finish <= start || body.isBlank()) null else Cue(start, finish, body, "ttml", index)
        }.toList()
    }

    private fun parseTtmlTime(value: String?): Long? {
        val normalized = value?.trim() ?: return null
        return when {
            normalized.matches(Regex("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?")) -> parseTime(normalized)
            normalized.endsWith("ms", true) -> normalized.dropLast(2).toDoubleOrNull()?.toLong()
            normalized.endsWith("s", true) -> normalized.dropLast(1).toDoubleOrNull()?.times(1000L)?.toLong()
            else -> null
        }
    }

    private fun stripSubtitleMarkup(value: String): String = value
        .replace(Regex("(?s)\\{[^{}]*\\}"), "")
        .replace(Regex("(?is)<[^>]+>"), "")
        .replace("\\N", "\n")
        .trim()

    private fun readSubtitleText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        // A valid UTF-8 string can also decode as longer Shift_JIS text with no
        // replacement characters. Length is not evidence for that encoding.
        // NULs exclude BOM-less UTF-16 timelines from this fast path.
        if (bytes.none { it == 0.toByte() }) {
            val utf8 = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            }.getOrNull()
            if (utf8 != null) return utf8
        }

        val candidates = mutableListOf<Charset>()
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> candidates += Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> candidates += Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> candidates += Charsets.UTF_16BE
            else -> candidates += listOf(
                Charsets.UTF_8,
                Charset.forName("Shift_JIS"),
                Charset.forName("EUC-KR"),
                Charsets.UTF_16LE,
                Charsets.UTF_16BE,
            )
        }

        return candidates.distinct().mapNotNull { charset ->
            runCatching { String(bytes, charset).removePrefix("\uFEFF") }.getOrNull()
        }.maxByOrNull { decoded ->
            val replacement = decoded.count { it == '\uFFFD' }
            val controls = decoded.count { it.code in 0..8 || it.code in 14..31 }
            decoded.length - replacement * 20 - controls * 20
        }
    }

    private fun parseTime(value: String): Long {
        val normalized = value.trim().substringBefore(' ')
        val parts = normalized.split(':')
        return runCatching {
            when (parts.size) {
                3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000) +
                    (parts[2].replace(',', '.').toDouble() * 1000).toLong()
                2 -> (parts[0].toLong() * 60_000) +
                    (parts[1].replace(',', '.').toDouble() * 1000).toLong()
                else -> -1L
            }
        }.getOrDefault(-1L)
    }

    private fun assTime(ms: Long): String {
        val totalCentiseconds = ms.coerceAtLeast(0L) / 10L
        val hours = totalCentiseconds / 360000L
        val minutes = (totalCentiseconds / 6000L) % 60L
        val seconds = (totalCentiseconds / 100L) % 60L
        val centiseconds = totalCentiseconds % 100L
        return "%d:%02d:%02d.%02d".format(
            java.util.Locale.US,
            hours,
            minutes,
            seconds,
            centiseconds,
        )
    }

    private fun parseAssTime(value: String): Long {
        val parts = value.trim().split(':')
        return runCatching {
            if (parts.size != 3) return@runCatching -1L
            val seconds = parts[2].replace(',', '.').toDouble()
            ((parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000 +
                (seconds * 1000).toLong())
        }.getOrDefault(-1L)
    }

    companion object {
        private const val TAG = "RealtimeSubtitleTranslator"
    }
}

private const val NATIVE_HEADER = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,sans-serif,48,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,60,60,60,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
