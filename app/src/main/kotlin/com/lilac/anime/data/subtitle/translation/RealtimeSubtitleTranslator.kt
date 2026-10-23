package com.lilac.anime.data.subtitle.translation

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.translation.providers.LocalTranslator
import com.lilac.anime.player.LibassTranslationOverlay
import com.lilac.anime.player.MpvPlaybackManager
import com.lilac.anime.ui.settings.AiRuntimeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.charset.Charset

class RealtimeSubtitleTranslator(private val context: Context) {
    private data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val kind: String,
        val index: Int,
    )

    private val lock = Mutex()

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
    private var sourcePath: String? = null
    private var worker: Job? = null
    private var prefetchPositionMs: Long = 0L
    private var generation = 0

    data class ReadyEvent(
        val key: String,
        val startMs: Long,
        val endMs: Long,
        val assEventData: String,
    )

    suspend fun prepare(
        path: String?,
        positionMs: Long,
        scope: CoroutineScope,
        providerId: String = "local",
    ) {
        // PlayerScreen used to pass localSubtitle while the overlay rendered
        // engine.currentSubtitleFilePath. When those differed, translation was
        // performed against a stale/empty file. Always prefer the actual mpv
        // external subtitle file when it exists.
        val requestedPath = path?.removePrefix("file://")
        val enginePath = MpvPlaybackManager.currentEngineOrNull()
            ?.currentSubtitleFilePath
            ?.removePrefix("file://")
        val resolvedPath = sequenceOf(enginePath, requestedPath)
            .filterNotNull()
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.absolutePath

        val file = resolvedPath?.let(::File)
        if (file == null) {
            Log.w(
                TAG,
                "SESSION_PREPARE_NO_FILE provider=$providerId requested=$requestedPath engine=$enginePath; clearing stale subtitle session",
            )
            clear()
            return
        }

        val content = readSubtitleText(file)
        if (content == null) {
            Log.e(TAG, "SESSION_READ_FAILED path=${file.absolutePath}")
            clear()
            return
        }

        val parsed = runCatching { parse(content, file.extension.lowercase()) }
            .onFailure { Log.e(TAG, "PARSE_FAILED path=${file.absolutePath}", it) }
            .getOrDefault(emptyList())
        if (parsed.isEmpty()) {
            Log.w(
                TAG,
                "PARSE_EMPTY path=${file.absolutePath} ext=${file.extension} requested=$requestedPath engine=$enginePath",
            )
        }

        // Remove translated images from the previous mode/session immediately.
        LibassTranslationOverlay.clearActiveTranslatedOverlay()

        lock.withLock {
            generation++
            worker?.cancel()
            worker = null
            translationLock = Mutex()
            cache.clear()
            sourceContent = content
            sourcePath = file.absolutePath
            cues = parsed
            prefetchPositionMs = positionMs.coerceAtLeast(0L)
        }

        endProviderSessionQuietly()

        activeProviderId = providerId.trim().lowercase().ifBlank { "local" }
        activeProvider = TranslationManager.createProvider(context, activeProviderId)
        sessionProvider = activeProvider as? TranslationSessionProvider
        val localGeneration = lock.withLock { generation }

        Log.i(
            TAG,
            "SESSION_PREPARE provider=$activeProviderId cues=${parsed.size} file=${file.name} requested=$requestedPath engine=$enginePath positionMs=$positionMs",
        )

        worker = scope.launch(Dispatchers.IO) {
            prefetchLoop(localGeneration, positionMs.coerceAtLeast(0L))
        }
    }

    /** Playback-time lookup only. Translation runs only in the prefetch worker. */
    suspend fun updatePlaybackPosition(positionMs: Long) {
        lock.withLock { prefetchPositionMs = positionMs.coerceAtLeast(0L) }
    }

    suspend fun clear() {
        val oldWorker = lock.withLock {
            generation++
            val old = worker
            worker = null
            translationLock = Mutex()
            cache.clear()
            cues = emptyList()
            sourceContent = null
            sourcePath = null
            prefetchPositionMs = 0L
            old
        }
        oldWorker?.cancel()
        endProviderSessionQuietly()
        activeProviderId = "local"
        activeProvider = LocalTranslator(context)
        sessionProvider = null
        LibassTranslationOverlay.clearActiveTranslatedOverlay()
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
        sessionStarted = true
    }

    private suspend fun prefetchLoop(localGeneration: Int, initialPositionMs: Long) {
        var nextIndex = -1
        var lastPositionMs = initialPositionMs
        val failures = mutableMapOf<String, Int>()

        while (true) {
            if (localGeneration != lock.withLock { generation }) return
            val snapshot = lock.withLock { cues }
            if (snapshot.isEmpty()) {
                delay(250L)
                if (localGeneration != lock.withLock { generation }) return
                continue
            }

            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pref_ai_prefetch_enabled", true)) {
                // Do not terminate the worker permanently. The setting can be
                // enabled again while the same episode is still playing.
                delay(500L)
                continue
            }

            val prefetchAhead = prefs.getInt("pref_ai_prefetch_ahead", 10).coerceIn(0, 40)
            val contextCueCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 6)
            val batchSize = prefs.getInt("pref_ai_batch_size", 5).coerceIn(2, 8)
            val position = lock.withLock { prefetchPositionMs }
            val ordered = snapshot.sortedWith(
                compareBy<Cue> { it.startMs }.thenBy { it.endMs }.thenBy { it.index },
            )

            // Anchor on cue end time, not cue start time. This includes a cue
            // that is already on screen when Korean translation is enabled.
            val currentIndex = ordered.indexOfFirst { it.endMs >= position - 250L }
                .let { if (it < 0) ordered.lastIndex.coerceAtLeast(0) else it }

            // Rewind prefetch after a backwards seek. The previous monotonic
            // cursor could permanently skip an earlier cue that had failed or
            // had never been translated.
            if (position + 1500L < lastPositionMs) nextIndex = currentIndex
            lastPositionMs = position
            if (nextIndex < currentIndex) nextIndex = currentIndex

            val windowEnd = (currentIndex + prefetchAhead + 1).coerceAtMost(ordered.size)
            if (nextIndex >= windowEnd) {
                delay(150L)
                continue
            }

            while (
                nextIndex < windowEnd &&
                lock.withLock { cache.containsKey(cueCacheKey(ordered[nextIndex])) }
            ) {
                nextIndex++
            }
            if (nextIndex >= windowEnd) continue

            val selectedProvider = activeProvider
            val batchEnd = if (selectedProvider is LocalTranslator) {
                nextIndex + 1
            } else {
                minOf(windowEnd, nextIndex + batchSize)
            }
            val batch = mutableListOf<Cue>()
            for (candidate in ordered.subList(nextIndex, batchEnd)) {
                if (!lock.withLock { cache.containsKey(cueCacheKey(candidate)) }) {
                    batch += candidate
                }
            }
            nextIndex = batchEnd
            if (batch.isEmpty()) continue
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
                            ordered = ordered,
                            failures = failures,
                            localGeneration = localGeneration,
                            onRetryIndex = { retry -> nextIndex = minOf(nextIndex, retry) },
                        )
                    }
                }
            } catch (error: CancellationException) {
                Log.d(TAG, "PREFETCH_CANCELLED provider=$activeProviderId")
                throw error
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "PREFETCH_BATCH_FAILED provider=$activeProviderId cues=${batch.joinToString { it.index.toString() }}",
                    error,
                )
                var exhausted = false
                batch.forEach { cue ->
                    val key = cueCacheKey(cue)
                    val attempt = (failures[key] ?: 0) + 1
                    failures[key] = attempt
                    if (attempt < 3) {
                        nextIndex = minOf(nextIndex, ordered.indexOf(cue))
                    } else {
                        exhausted = true
                        Log.e(TAG, "PREFETCH_GIVE_UP provider=$activeProviderId cue=${cue.index} attempts=$attempt")
                    }
                }
                if (exhausted) {
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
            if (localGeneration == generation) cache[cueCacheKey(cue)] = result
        }
    }

    private suspend fun translateCloudCues(
        selectedProvider: TranslationProvider,
        batch: List<Cue>,
        ordered: List<Cue>,
        failures: MutableMap<String, Int>,
        localGeneration: Int,
        onRetryIndex: (Int) -> Unit,
    ) {
        val sources = batch.map { modelText(it.text, it.kind) }
        Log.i(
            TAG,
            "PREFETCH_TRANSLATE_BATCH provider=$activeProviderId model=${batch.size} cues=${batch.joinToString { it.index.toString() }}",
        )

        try {
            val results = translateCloudBatchWithFallback(selectedProvider, sources)
            if (results.size != batch.size || results.any { it.isBlank() }) {
                error("번역 응답 줄 수/내용 불일치 expected=${batch.size} actual=${results.size}")
            }
            lock.withLock {
                if (localGeneration == generation) {
                    batch.indices.forEach { index ->
                        cache[cueCacheKey(batch[index])] = results[index].trim()
                    }
                }
            }
            Log.i(TAG, "PREFETCH_TRANSLATE_BATCH_OK provider=$activeProviderId count=${results.size}")
        } catch (batchError: Throwable) {
            if (batchError is CancellationException) throw batchError
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
                try {
                    val single = translateCloudBatchWithFallback(
                        selectedProvider,
                        listOf(modelText(cue.text, cue.kind)),
                    ).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                        ?: error("단일 자막 번역 결과가 비어 있습니다.")
                    lock.withLock {
                        if (localGeneration == generation) cache[cueCacheKey(cue)] = single
                    }
                    Log.i(TAG, "PREFETCH_SINGLE_FALLBACK_OK provider=$activeProviderId cue=${cue.index}")
                } catch (singleError: Throwable) {
                    if (singleError is CancellationException) throw singleError
                    val key = cueCacheKey(cue)
                    val attempt = (failures[key] ?: 0) + 1
                    failures[key] = attempt
                    Log.e(
                        TAG,
                        "PREFETCH_SINGLE_FALLBACK_FAILED provider=$activeProviderId cue=${cue.index} attempt=$attempt",
                        singleError,
                    )
                    if (attempt < 3) {
                        onRetryIndex(ordered.indexOf(cue))
                    } else {
                        AiRuntimeDiagnostics.record(
                            context,
                            "실시간 자막 단일 번역 ($activeProviderId, cue=${cue.index})",
                            singleError,
                        )
                    }
                }
            }
        }
    }

    private suspend fun translateCloudBatchWithFallback(
        primaryProvider: TranslationProvider,
        lines: List<String>,
    ): List<String> {
        val primaryId = primaryProvider.id.lowercase()
        val providerIds = (
            listOf(primaryId) +
                listOf("gemini", "openai", "deepl", "qwen").filter { it != primaryId }
            ).distinct()
        val failures = mutableListOf<String>()
        var lastError: Throwable? = null

        for (providerId in providerIds) {
            if (providerId != primaryId && !SecureApiKeyStore.has(context, providerId)) continue
            val provider = if (providerId == primaryId) {
                primaryProvider
            } else {
                TranslationManager.createProvider(context, providerId)
            }

            for (attempt in 0..2) {
                try {
                    Log.i(
                        TAG,
                        "CLOUD_PROVIDER_ATTEMPT provider=$providerId attempt=${attempt + 1}/3 lines=${lines.size}",
                    )
                    val results = provider.translateBatch(lines)
                    if (results.size != lines.size || results.any { it.isBlank() }) {
                        error("$providerId 응답 줄 수/내용 불일치 expected=${lines.size} actual=${results.size}")
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

        val dialogueLines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .filter { it.trimStart().startsWith("Dialogue:", true) }
            .toList()
        val line = dialogueLines.getOrNull(cue.index)
        val body = line
            ?.substringAfter(':', "")
            ?.trimStart()
            ?.split(',', limit = 10)

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

    private fun modelText(text: String, kind: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lineBreaksNormalized = if (kind in setOf("srt", "vtt", "sbv")) {
            normalized.replace("\n", "\\N")
        } else {
            normalized
        }
        return lineBreaksNormalized
            .replace(Regex("(?s)\\{[^{}]*\\}"), "")
            .replace(Regex("(?is)<[^>]+>"), "")
            .trim()
    }

    private fun sanitizeTranslatedText(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("(?is)<[^>]+>"), "")
        .replace(Regex("(?s)\\{[^{}]*\\}"), "")
        .replace(Regex("(?im)^\\s*<LILAC_\\d+>\\s*"), "")
        .replace("&nbsp;", " ", true)
        .replace("&amp;", "&", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .trim()

    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = sanitizeTranslatedText(translated)
        val isAss = kind == "ass" || kind == "ssa"
        if (!isAss) {
            return normalized.replace("\\N", "\n").replace("\n", "\\N")
        }

        val tagRegex = Regex("\\{[^}]*\\}")
        val matches = tagRegex.findAll(original).toList()
        val translatedText = normalized.replace("\\N", "\n").replace("\n", "\\N")
        if (matches.isEmpty()) return translatedText

        val visibleLength = original.replace(tagRegex, "").length.coerceAtLeast(1)
        val insertions = matches.map { match ->
            val before = original.substring(0, match.range.first).replace(tagRegex, "")
            val ratio = before.length.toDouble() / visibleLength.toDouble()
            val position = (translatedText.length * ratio).toInt().coerceIn(0, translatedText.length)
            position to match.value
        }.sortedWith(compareBy<Pair<Int, String>> { it.first })

        val out = StringBuilder()
        var cursor = 0
        insertions.groupBy { it.first }.toSortedMap().forEach { (position, tags) ->
            val p = position.coerceIn(cursor, translatedText.length)
            if (p > cursor) out.append(translatedText.substring(cursor, p))
            tags.forEach { out.append(it.second) }
            cursor = p
        }
        if (cursor < translatedText.length) out.append(translatedText.substring(cursor))
        return out.toString()
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
        val result = mutableListOf<Cue>()
        var i = 0
        var index = 0
        val timing = Regex("^\\s*([^\\s]+)\\s*-->\\s*([^\\s]+)(?:\\s+.*)?$")

        while (i < lines.size) {
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

            val body = buildList {
                var j = i + 1
                while (j < lines.size && !timing.matches(lines[j])) {
                    val line = lines[j]
                    if (line.isBlank()) break
                    if (!(kind == "vtt" && line.trim().startsWith("NOTE", true))) add(line)
                    j++
                }
            }.joinToString("\\N").trim()

            if (body.isNotBlank()) result += Cue(start, end, body, kind, index++)
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
            if (start >= 0L && end > start && visibleLetter.containsMatchIn(visible)) {
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
            val body = match.groupValues[2]
                .replace(Regex("(?is)<br\\s*/?>"), "\\N")
                .replace(Regex("(?is)<[^>]+>"), "")
                .replace("&nbsp;", " ", true)
                .trim()
            if (body.isBlank() || next <= start) null else Cue(start, next, body, "smi", index)
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
