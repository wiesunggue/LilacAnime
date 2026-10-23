from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: expected source block not found in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: str, old: str, new: str, minimum: int, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{label}: expected at least {minimum} matches, found {count} in {path}")
    p.write_text(text.replace(old, new), encoding="utf-8")


OVERLAY = "app/src/main/kotlin/com/lilac/anime/player/LibassTranslationOverlay.kt"
PLAYER = "app/src/main/kotlin/com/lilac/anime/player/PlayerScreen.kt"
REALTIME = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslator.kt"
FORMAT = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/SubtitleFormatTranslator.kt"
VTT_ASS = "app/src/main/kotlin/com/lilac/anime/data/subtitle/VttToAss.kt"
SYNC = "app/src/main/kotlin/com/lilac/anime/data/subtitle/SubtitleSync.kt"
DOWNLOADER = "app/src/main/kotlin/com/lilac/anime/data/subtitle/SubtitleDownloader.kt"

# ---------------------------------------------------------------------------
# libass translated-overlay stability + subtitle size support
# ---------------------------------------------------------------------------
replace_once(
    OVERLAY,
    "    private var events = emptyList<EventRange>()\n",
    "    private val events = mutableListOf<EventRange>()\n",
    "overlay mutable event list",
)
replace_once(
    OVERLAY,
    "    private var videoTop = 0\n    private var videoWidthPx = 0\n    private var videoHeightPx = 0\n",
    "    private var videoTop = 0\n    private var videoWidthPx = 0\n    private var videoHeightPx = 0\n    private var subtitleScale = 1.0f\n",
    "overlay subtitle scale state",
)
replace_once(
    OVERLAY,
    "    fun setSource(path: String?) {\n        val normalized = path?.removePrefix(\"file://\")?.takeIf { File(it).isFile }\n        if (normalized == sourcePath) return\n",
    """    fun setSubtitleScale(percent: Float) {
        val scale = (percent / 100f).coerceIn(0.5f, 2.0f)
        if (kotlin.math.abs(subtitleScale - scale) < 0.001f) return
        subtitleScale = scale
        renderer?.let { render ->
            runCatching { render.setFontScale(scale) }
                .onFailure { Log.e(TAG, \"LIBASS_SET_FONT_SCALE_FAILED scale=$scale\", it) }
        }
        if (isReady()) scheduleRender()
    }

    fun setSource(path: String?) {
        val normalized = path?.removePrefix(\"file://\")?.takeIf { File(it).isFile }
        // A detached/re-attached AndroidView may retain the same sourcePath after
        // releaseLibass(). Only short-circuit when the renderer is actually alive.
        if (normalized == sourcePath && isReady()) return
""",
    "overlay source recovery + scale API",
)
replace_all(OVERLAY, "        events = emptyList()\n", "        events.clear()\n", 2, "overlay event clearing")
replace_once(
    OVERLAY,
    "            r.setStorageSize(w, h)\n            r.setFrameSize(w, h)\n",
    "            r.setStorageSize(w, h)\n            r.setFrameSize(w, h)\n            r.setFontScale(subtitleScale)\n",
    "overlay initial font scale",
)
replace_once(
    OVERLAY,
    "            events = events + EventRange(startMs, endMs)\n",
    "            events.add(EventRange(startMs, endMs))\n",
    "overlay O(n^2) event append",
)
replace_once(
    OVERLAY,
    "    fun hasActiveTranslatedEvent(positionMs: Long): Boolean =\n        events.any { positionMs >= it.startMs && positionMs <= it.endMs }\n",
    "    fun hasActiveTranslatedEvent(positionMs: Long): Boolean =\n        events.any { positionMs >= it.startMs && positionMs < it.endMs }\n",
    "overlay end-boundary consistency",
)
replace_once(
    OVERLAY,
    "    override fun onDetachedFromWindow() {\n        releaseLibass()\n        super.onDetachedFromWindow()\n    }\n",
    """    override fun onDetachedFromWindow() {
        releaseLibass()
        sourcePath = null
        events.clear()
        hasRenderedFrame = false
        super.onDetachedFromWindow()
    }
""",
    "overlay detach recovery",
)

# ---------------------------------------------------------------------------
# Compose player: never consume translated events before libass is ready.
# ---------------------------------------------------------------------------
replace_once(
    PLAYER,
    "    var realtimeSubtitleText by remember { mutableStateOf(\"\") }\n    var libassTranslationOverlay by remember { mutableStateOf<LibassTranslationOverlay?>(null) }\n\n",
    """    var realtimeSubtitleText by remember { mutableStateOf(\"\") }
    var libassTranslationOverlay by remember { mutableStateOf<LibassTranslationOverlay?>(null) }

    LaunchedEffect(subtitleSize, libassTranslationOverlay) {
        libassTranslationOverlay?.setSubtitleScale(subtitleSize)
    }

""",
    "player translated subtitle size binding",
)
replace_once(
    PLAYER,
    """            val source = engine.currentSubtitleFilePath ?: localSubtitle
            libassTranslationOverlay?.setSource(source)
            realtimeTranslator.consumeReadyTranslatedEvents().forEach { event ->
                libassTranslationOverlay?.addTranslatedEvent(
                    event.assEventData,
                    event.startMs,
                    event.endMs,
                )
            }
            val overlay = libassTranslationOverlay
""",
    """            val source = engine.currentSubtitleFilePath ?: localSubtitle
            val overlay = libassTranslationOverlay
            overlay?.setSource(source)
            // consumeReadyTranslatedEvents() marks events as emitted. Calling it
            // before AndroidView/libass is ready permanently drops those cues.
            if (overlay?.isReady() == true) {
                realtimeTranslator.consumeReadyTranslatedEvents().forEach { event ->
                    overlay.addTranslatedEvent(
                        event.assEventData,
                        event.startMs,
                        event.endMs,
                    )
                }
            }
""",
    "player overlay ready-event race",
)

# ---------------------------------------------------------------------------
# Realtime translation: seek-safe prefetch, robust decoding, and no raw HTML
# leaking into an ASS/libass event (the observed <font> bug).
# ---------------------------------------------------------------------------
replace_once(
    REALTIME,
    "import java.io.File\n",
    "import java.io.File\nimport java.nio.charset.Charset\n",
    "realtime charset import",
)
replace_once(
    REALTIME,
    """        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            Log.e(\"RealtimeSubtitleTranslator\", \"SESSION_READ_FAILED path=${file.absolutePath}\", it)
            clear()
            return
        }
""",
    """        val content = readSubtitleText(file) ?: run {
            Log.e(\"RealtimeSubtitleTranslator\", \"SESSION_READ_FAILED path=${file.absolutePath}\")
            clear()
            return
        }
""",
    "realtime subtitle decoding",
)
replace_once(
    REALTIME,
    """    suspend fun updatePlaybackPosition(positionMs: Long) {
        // Playback events only advance the prefetch window. They never call the
        // translator and never perform a one-line translation.
        prefetchPositionMs = positionMs
    }
""",
    """    suspend fun updatePlaybackPosition(positionMs: Long) {
        // Keep all access to this cross-coroutine value under the same mutex.
        // Playback events only advance the prefetch window; they never call the model.
        lock.withLock { prefetchPositionMs = positionMs.coerceAtLeast(0L) }
    }
""",
    "realtime prefetch position synchronization",
)
replace_once(
    REALTIME,
    """    private suspend fun prefetchLoop(localGeneration: Int, initialPositionMs: Long) {
        var nextIndex = -1
        val failures = mutableMapOf<String, Int>()
""",
    """    private suspend fun prefetchLoop(localGeneration: Int, initialPositionMs: Long) {
        var nextIndex = -1
        var lastPositionMs = initialPositionMs
        val failures = mutableMapOf<String, Int>()
""",
    "realtime backward-seek state",
)
replace_once(
    REALTIME,
    """            if (!prefs.getBoolean(\"pref_ai_prefetch_enabled\", true)) {
                Log.i(\"RealtimeSubtitleTranslator\", \"PREFETCH_DISABLED provider=$activeProviderId\")
                return
            }
""",
    """            if (!prefs.getBoolean(\"pref_ai_prefetch_enabled\", true)) {
                // Do not kill the worker permanently: the setting may be toggled
                // back on while the same subtitle session is still playing.
                delay(500L)
                continue
            }
""",
    "realtime prefetch re-enable",
)
replace_once(
    REALTIME,
    """            val position = lock.withLock { prefetchPositionMs }
            val ordered = snapshot.sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
            val currentIndex = ordered.indexOfFirst { it.startMs >= position - 500L }
                .let { if (it < 0) ordered.lastIndex.coerceAtLeast(0) else it }
            if (nextIndex < currentIndex) nextIndex = currentIndex
""",
    """            val position = lock.withLock { prefetchPositionMs }
            val ordered = snapshot.sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
            // A cue that started more than 500 ms ago can still be on-screen.
            // Anchor on cue END time so enabling translation mid-cue does not skip it.
            val currentIndex = ordered.indexOfFirst { it.endMs >= position - 250L }
                .let { if (it < 0) ordered.lastIndex.coerceAtLeast(0) else it }
            // nextIndex was monotonic, so a backward seek could never translate an
            // earlier uncached/failed cue. Rewind the worker when playback rewinds.
            if (position + 1500L < lastPositionMs) nextIndex = currentIndex
            lastPositionMs = position
            if (nextIndex < currentIndex) nextIndex = currentIndex
""",
    "realtime current cue + backward seek",
)
replace_once(
    REALTIME,
    """    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = translated.replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n')
        val tagRegex = if (kind == \"ass\") Regex(\"\\\\{[^}]*\\\\}\") else Regex(\"(?is)<[^>]+>\")
        val matches = tagRegex.findAll(original).toList()
        if (matches.isEmpty()) return if (kind == \"ass\") normalized.replace(\"\\n\", \"\\\\N\") else normalized
        // ASS override tags belong at the same logical positions as the original
        // cue. Do not let a model-generated label or echoed context become part of
        // the subtitle payload.
        val visibleLength = original.replace(tagRegex, \"\").length.coerceAtLeast(1)
        val translatedText = if (kind == \"ass\") normalized.replace(\"\\n\", \"\\\\N\") else normalized
        val insertions = matches.map { match ->
            val before = original.substring(0, match.range.first).replace(tagRegex, \"\")
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
""",
    """    private fun sanitizeTranslatedText(value: String): String = value
        .replace(\"\\r\\n\", \"\\n\")
        .replace('\\r', '\\n')
        // Models occasionally echo source markup. Raw HTML is not valid ASS text
        // and was the cause of transient literal strings such as <font ...>.
        .replace(Regex(\"(?is)<[^>]+>\"), \"\")
        .replace(Regex(\"(?s)\\\\{[^{}]*\\\\}\"), \"\")
        .replace(Regex(\"(?im)^\\\\s*<LILAC_\\\\d+>\\\\s*\"), \"\")
        .replace(\"&nbsp;\", \" \", true)
        .replace(\"&amp;\", \"&\", true)
        .replace(\"&lt;\", \"<\", true)
        .replace(\"&gt;\", \">\", true)
        .replace(\"&quot;\", \"\\\"\", true)
        .replace(\"&#39;\", \"'\", true)
        .trim()

    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = sanitizeTranslatedText(translated)
        val isAss = kind == \"ass\" || kind == \"ssa\"
        if (!isAss) {
            // Non-ASS source markup (<font>, <i>, WebVTT voice/class tags, ...)
            // must never be copied verbatim into an ASS event. Keep clean text and
            // preserve only line breaks; libass supplies styling from the track.
            return normalized.replace(\"\\\\N\", \"\\n\").replace(\"\\n\", \"\\\\N\")
        }

        val tagRegex = Regex(\"\\\\{[^}]*\\\\}\")
        val matches = tagRegex.findAll(original).toList()
        val translatedText = normalized.replace(\"\\\\N\", \"\\n\").replace(\"\\n\", \"\\\\N\")
        if (matches.isEmpty()) return translatedText

        // Preserve only ORIGINAL ASS override tags, at approximately the same
        // logical positions. Model-generated tags were stripped above.
        val visibleLength = original.replace(tagRegex, \"\").length.coerceAtLeast(1)
        val insertions = matches.map { match ->
            val before = original.substring(0, match.range.first).replace(tagRegex, \"\")
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
""",
    "realtime raw HTML/font tag leak",
)
replace_once(
    REALTIME,
    """    private fun parseAss(text: String): List<Cue> {
        val all = mutableListOf<Cue>()
        val japanese = Regex(\"[\\\\u3040-\\\\u30ff\\\\u3400-\\\\u4dbf\\\\u4e00-\\\\u9fff々ー]\")
        var ordinal = 0
        text.lines().forEach { line ->
            if (!line.startsWith(\"Dialogue:\", true)) return@forEach
            val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
            if (parts.size < 10) { ordinal++; return@forEach }
            val start = parseAssTime(parts[1]); val end = parseAssTime(parts[2])
            val body = parts[9].trim()
            if (start >= 0 && end > start && body.isNotBlank() && japanese.containsMatchIn(stripSubtitleMarkup(body))) {
                all += Cue(start, end, body, \"ass\", ordinal)
            }
            ordinal++
        }
        return all
    }
""",
    """    private fun parseAss(text: String): List<Cue> {
        val all = mutableListOf<Cue>()
        val visibleLetter = Regex(\"\\\\p{L}\")
        var ordinal = 0
        text.lines().forEach { line ->
            if (!line.startsWith(\"Dialogue:\", true)) return@forEach
            val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
            if (parts.size < 10) { ordinal++; return@forEach }
            val start = parseAssTime(parts[1]); val end = parseAssTime(parts[2])
            val body = parts[9].trim()
            val visible = stripSubtitleMarkup(body)
            // English ASS tracks are valid translation sources too. The former
            // Japanese-only gate made them parse as an empty subtitle file.
            if (start >= 0 && end > start && visibleLetter.containsMatchIn(visible)) {
                all += Cue(start, end, body, \"ass\", ordinal)
            }
            ordinal++
        }
        return all
    }
""",
    "realtime English ASS support",
)
replace_once(
    REALTIME,
    "    private fun parse(text: String, ext: String): List<Cue> {\n",
    """    private fun readSubtitleText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val candidates = mutableListOf<Charset>()
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> candidates += Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> candidates += Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> candidates += Charsets.UTF_16BE
            else -> candidates += listOf(Charsets.UTF_8, Charset.forName(\"Shift_JIS\"), Charset.forName(\"EUC-KR\"), Charsets.UTF_16LE, Charsets.UTF_16BE)
        }
        return candidates.distinct().mapNotNull { charset ->
            runCatching { String(bytes, charset).removePrefix(\"\\uFEFF\") }.getOrNull()
        }.maxByOrNull { decoded ->
            val replacement = decoded.count { it == '\\uFFFD' }
            val controls = decoded.count { it.code in 0..8 || it.code in 14..31 }
            decoded.length - replacement * 20 - controls * 20
        }
    }

    private fun parse(text: String, ext: String): List<Cue> {
""",
    "realtime robust decoder helper",
)

# ---------------------------------------------------------------------------
# Full-file translation path: English ASS support and tag placement stability.
# ---------------------------------------------------------------------------
replace_once(
    FORMAT,
    """                } catch (error: Throwable) {
                    Log.e(\"SubtitleProfile\", \"SESSION_CLEANUP_FAILED type=${error::class.java.name} message=${error.message}\", error)
                    throw error
                }
""",
    """                } catch (error: Throwable) {
                    // Cleanup failure must not discard a translation that already
                    // completed successfully.
                    Log.e(\"SubtitleProfile\", \"SESSION_CLEANUP_FAILED type=${error::class.java.name} message=${error.message}\", error)
                }
""",
    "format translator cleanup masking",
)
replace_once(
    FORMAT,
    """    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = translated.replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n')
        return when (kind) {
            \"ass\" -> {
                val tags = Regex(\"\\\\{[^}]*\\\\}\").findAll(original).map { it.value }.toList()
                val text = normalized.replace(\"\\n\", \"\\\\N\")
                if (tags.isEmpty()) text else tags.joinToString(\"\") + text
            }
            else -> {
                val tags = Regex(\"(?is)<[^>]+>\").findAll(original).map { it.value }.toList()
                if (tags.isEmpty()) normalized else tags.joinToString(\"\") + normalized
            }
        }
    }
""",
    """    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = translated.replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n')
        val tagRegex = if (kind == \"ass\" || kind == \"ssa\") Regex(\"\\\\{[^}]*\\\\}\") else Regex(\"(?is)<[^>]+>\")
        val matches = tagRegex.findAll(original).toList()
        val translatedText = if (kind == \"ass\" || kind == \"ssa\") normalized.replace(\"\\n\", \"\\\\N\") else normalized
        if (matches.isEmpty()) return translatedText
        val visibleLength = original.replace(tagRegex, \"\").length.coerceAtLeast(1)
        val insertions = matches.map { match ->
            val before = original.substring(0, match.range.first).replace(tagRegex, \"\")
            val ratio = before.length.toDouble() / visibleLength.toDouble()
            val position = (translatedText.length * ratio).toInt().coerceIn(0, translatedText.length)
            position to match.value
        }.sortedBy { it.first }
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
""",
    "format translator tag placement",
)
# Replace the Japanese-only ASS parser gate with a per-event visible-letter gate.
start = "    private fun parseAss(c: String): List<Cue> {\n"
end = "\n    private fun parseSrt(c: String): List<Cue> {\n"
p = Path(FORMAT)
s = p.read_text(encoding="utf-8")
a = s.find(start)
b = s.find(end, a + len(start))
if a < 0 or b < 0:
    raise SystemExit("format ASS parser block not found")
new_parse_ass = """    private fun parseAss(c: String): List<Cue> {
        val lines = c.lines()
        val visibleLetter = Regex(\"\\p{L}\")
        var cueIndex = 0
        return lines.mapNotNull { line ->
            if (!line.startsWith(\"Dialogue:\", true)) return@mapNotNull null
            val body = line.substringAfter(':').trimStart()
            val parts = body.split(',', limit = 10)
            if (parts.size < 10) return@mapNotNull null
            val startMs = assTime(parts[1]) ?: return@mapNotNull null
            val endMs = assTime(parts[2]) ?: return@mapNotNull null
            val rawText = parts[9].trim()
            val visible = rawText
                .replace(Regex(\"(?s)\\{[^}]*\\}\"), \"\")
                .replace(Regex(\"(?is)<[^>]+>\"), \"\")
                .replace(\"\\N\", \" \" )
                .trim()
            if (endMs <= startMs || !visibleLetter.containsMatchIn(visible)) return@mapNotNull null
            Cue(startMs, endMs, rawText, line, \"ass\", cueIndex++)
        }
    }
"""
p.write_text(s[:a] + new_parse_ass + s[b:], encoding="utf-8")

# ---------------------------------------------------------------------------
# VTT -> ASS conversion: preserve real line breaks and decode common entities.
# ---------------------------------------------------------------------------
replace_once(
    VTT_ASS,
    "                        if (isNotEmpty()) append(\"\\\\N\")\n",
    "                        if (isNotEmpty()) append('\\n')\n",
    "VTT real line break",
)
replace_once(
    VTT_ASS,
    """private fun toAssText(raw: String): String = raw
    .replace(\"\\\\\", \"\\\\\\\\\")
    .replace(\"<i>\", \"{\\\\i1}\", true).replace(\"</i>\", \"{\\\\i0}\", true)
    .replace(\"<b>\", \"{\\\\b1}\", true).replace(\"</b>\", \"{\\\\b0}\", true)
    .replace(\"<u>\", \"{\\\\u1}\", true).replace(\"</u>\", \"{\\\\u0}\", true)
    .replace(Regex(\"<[^>]+>\"), \"\")
""",
    """private fun toAssText(raw: String): String = raw
    .replace(\"\\\\\", \"\\\\\\\\\")
    .replace(\"<i>\", \"{\\\\i1}\", true).replace(\"</i>\", \"{\\\\i0}\", true)
    .replace(\"<b>\", \"{\\\\b1}\", true).replace(\"</b>\", \"{\\\\b0}\", true)
    .replace(\"<u>\", \"{\\\\u1}\", true).replace(\"</u>\", \"{\\\\u0}\", true)
    .replace(Regex(\"(?is)<[^>]+>\"), \"\")
    .replace(\"&nbsp;\", \" \", true)
    .replace(\"&amp;\", \"&\", true)
    .replace(\"&lt;\", \"<\", true)
    .replace(\"&gt;\", \">\", true)
    .replace(\"&quot;\", \"\\\"\", true)
    .replace(\"&#39;\", \"'\", true)
    .replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n')
    .replace(\"\\n\", \"\\\\N\")
""",
    "VTT ASS markup/entity conversion",
)

# ---------------------------------------------------------------------------
# Subtitle sync: standard WebVTT MM:SS.mmm support + collision-free cache files.
# ---------------------------------------------------------------------------
replace_once(
    SYNC,
    "    val timePart = Regex(\"\"\"\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{3}\"\"\")\n",
    "    val timePart = Regex(\"\"\"(?:\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{3}\"\"\")\n",
    "subtitle sync WebVTT short time regex",
)
replace_once(
    SYNC,
    """        else timePart.replace(line) { match ->
            formatClock(parseClock(match.value) + offsetMs, '.', 3)
        }
""",
    """        else timePart.replace(line) { match ->
            val separator = if (match.value.contains(',')) ',' else '.'
            formatClock(parseClock(match.value) + offsetMs, separator, 3)
        }
""",
    "subtitle sync separator preservation",
)
replace_once(
    SYNC,
    """private fun parseClock(value: String): Long {
    val normalized = value.trim().replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return 0L
    val seconds = parts[2].split('.', limit = 2)
    val fraction = seconds.getOrNull(1).orEmpty().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return (parts[0].toLongOrNull() ?: 0L) * 3_600_000L +
        (parts[1].toLongOrNull() ?: 0L) * 60_000L +
        (seconds[0].toLongOrNull() ?: 0L) * 1_000L + fraction
}
""",
    """private fun parseClock(value: String): Long {
    val normalized = value.trim().replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size !in 2..3) return 0L
    val seconds = parts.last().split('.', limit = 2)
    val fraction = seconds.getOrNull(1).orEmpty().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: 0L else 0L
    val minutes = parts[parts.size - 2].toLongOrNull() ?: 0L
    return hours * 3_600_000L + minutes * 60_000L +
        (seconds[0].toLongOrNull() ?: 0L) * 1_000L + fraction
}
""",
    "subtitle sync parse MM:SS",
)
replace_once(
    SYNC,
    """        val safeAnimeId = animeId.replace(Regex(\"[^A-Za-z0-9._-]\"), \"_\")
        val safeOffset = if (offsetMs >= 0) \"p$offsetMs\" else \"m${-offsetMs}\"
        val target = File(
            dir,
            \"${safeAnimeId}_${episodeNumber}_${extension}_${safeOffset}.$extension\"
        )
""",
    """        val safeAnimeId = animeId.replace(Regex(\"[^A-Za-z0-9._-]\"), \"_\")
        val safeEpisodeKey = episodeKey.replace(Regex(\"[^A-Za-z0-9._-]\"), \"_\")
        val sourceKey = subtitlePath.hashCode().toUInt().toString(16)
        val safeOffset = if (offsetMs >= 0) \"p$offsetMs\" else \"m${-offsetMs}\"
        val target = File(
            dir,
            \"${safeAnimeId}_${episodeNumber}_${safeEpisodeKey}_${sourceKey}_${extension}_${safeOffset}.$extension\"
        )
""",
    "subtitle sync cache collision",
)

# ---------------------------------------------------------------------------
# Remote subtitle downloader: do not save ASS/SMI/TTML content with a .vtt name.
# ---------------------------------------------------------------------------
replace_once(
    DOWNLOADER,
    """                val normalized = text.trimStart()
                val isWebVtt = normalized.startsWith(\"WEBVTT\", ignoreCase = true)
                val isSrt = Regex(\"(?m)^\\\\d+\\\\s*$\").containsMatchIn(normalized) &&
                    normalized.contains(\" --> \")
                val extension = if (isSrt && !isWebVtt) \"srt\" else \"vtt\"
                val payload = if (isWebVtt) {
""",
    """                val normalized = text.trimStart()
                val isWebVtt = normalized.startsWith(\"WEBVTT\", ignoreCase = true)
                val isAss = Regex(\"(?im)^\\\\s*\\\\[Script Info\\\\]|^\\\\s*(?:Dialogue|Comment):\").containsMatchIn(normalized)
                val isSmi = Regex(\"(?is)<SAMI|<SYNC\\\\s+Start\\\\s*=\").containsMatchIn(normalized)
                val isTtml = Regex(\"(?is)<tt(?:\\\\s|>)|<ttml|xmlns=[\\\"'][^\\\"']*ttml\").containsMatchIn(normalized)
                val isSrt = Regex(\"(?m)^\\\\d+\\\\s*$\").containsMatchIn(normalized) && normalized.contains(\" --> \")
                val urlExtension = runCatching {
                    java.net.URI(vttUrl).path.substringAfterLast('.').lowercase(Locale.ROOT)
                        .takeIf { it in setOf(\"vtt\", \"srt\", \"ass\", \"ssa\", \"smi\", \"sami\", \"ttml\", \"xml\") }
                }.getOrNull()
                val extension = when {
                    isWebVtt -> \"vtt\"
                    isAss -> \"ass\"
                    isSmi -> \"smi\"
                    isTtml -> \"ttml\"
                    isSrt -> \"srt\"
                    urlExtension != null -> urlExtension
                    else -> \"vtt\"
                }
                val payload = if (isWebVtt) {
""",
    "remote subtitle format sniffing",
)

print("Applied LilacAnime subtitle/player stability fixes successfully")
