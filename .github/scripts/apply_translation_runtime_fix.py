from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: expected source block not found in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


OVERLAY = "app/src/main/kotlin/com/lilac/anime/player/LibassTranslationOverlay.kt"
PLAYER = "app/src/main/kotlin/com/lilac/anime/player/PlayerScreen.kt"
REALTIME = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslator.kt"

# ---------------------------------------------------------------------------
# Subtitle size: Compose state is the single source of truth.
# The overlay previously re-read a different SharedPreferences key every frame,
# which could overwrite the slider value back to 100% immediately.
# ---------------------------------------------------------------------------
replace_once(
    OVERLAY,
    '''        private const val FRAME_INTERVAL_MS = 33L
        private const val PREF_NAME = "lilac_offline_store"
        private const val PREF_SUBTITLE_SIZE = "pref_subtitle_size"
''',
    '''        private const val FRAME_INTERVAL_MS = 33L
''',
    "remove stale subtitle-size preference constants",
)
replace_once(
    OVERLAY,
    '''    private val playerPrefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

''',
    '''''',
    "remove stale subtitle-size preferences",
)
replace_once(
    OVERLAY,
    '''    /**
     * Direct binding hook for Compose. The overlay also polls the persisted
     * setting every rendered frame, so translated subtitle size still follows
     * the normal slider even if a Compose recomposition is missed.
     */
    fun setSubtitleScale(percent: Float) {
        applySubtitleScale((percent / 100f).coerceIn(0.5f, 2.0f), force = true)
    }

    private fun persistedSubtitleScale(): Float =
        (playerPrefs.getFloat(PREF_SUBTITLE_SIZE, 100f).coerceIn(50f, 200f) / 100f)

    private fun refreshPersistedSubtitleScale() {
        applySubtitleScale(persistedSubtitleScale(), force = false)
    }
''',
    '''    /** The PlayerScreen subtitleSize state is the single source of truth. */
    fun setSubtitleScale(percent: Float) {
        applySubtitleScale((percent / 100f).coerceIn(0.5f, 2.0f), force = true)
    }
''',
    "remove per-frame persisted subtitle-size override",
)
replace_once(
    OVERLAY,
    '''            subtitleScale = persistedSubtitleScale()
            r.setFontScale(subtitleScale)
''',
    '''            r.setFontScale(subtitleScale)
''',
    "preserve direct subtitle scale across renderer recreation",
)
replace_once(
    OVERLAY,
    '''        refreshPersistedSubtitleScale()
        ensureBitmap()
''',
    '''        ensureBitmap()
''',
    "stop resetting subtitle scale while rendering",
)

# Bind the current slider value immediately, on every Compose change, and when
# the AndroidView is created/reused. This handles both phone and TV sliders.
replace_once(
    PLAYER,
    '''    var realtimeSubtitleText by remember { mutableStateOf("") }
    var libassTranslationOverlay by remember { mutableStateOf<LibassTranslationOverlay?>(null) }

''',
    '''    var realtimeSubtitleText by remember { mutableStateOf("") }
    var libassTranslationOverlay by remember { mutableStateOf<LibassTranslationOverlay?>(null) }

    LaunchedEffect(subtitleSize, libassTranslationOverlay) {
        libassTranslationOverlay?.setSubtitleScale(subtitleSize)
    }

''',
    "bind translated subtitle size state",
)
replace_once(
    PLAYER,
    '''                LibassTranslationOverlay(ctx).also { view ->
                    libassTranslationOverlay = view
                    view.setSource(engine.currentSubtitleFilePath ?: localSubtitle)
''',
    '''                LibassTranslationOverlay(ctx).also { view ->
                    libassTranslationOverlay = view
                    view.setSubtitleScale(subtitleSize)
                    view.setSource(engine.currentSubtitleFilePath ?: localSubtitle)
''',
    "overlay factory subtitle scale",
)
replace_once(
    PLAYER,
    '''            update = { view ->
                libassTranslationOverlay = view
                val aspect = engine.videoDisplayAspect.takeIf { it > 0.01 }
''',
    '''            update = { view ->
                libassTranslationOverlay = view
                view.setSubtitleScale(subtitleSize)
                val aspect = engine.videoDisplayAspect.takeIf { it > 0.01 }
''',
    "overlay update subtitle scale",
)

# ---------------------------------------------------------------------------
# Translation: recover when mpv's subtitle path becomes available after the
# one-shot prepare effect. The polling loop already sees that path; make it
# idempotently ensure the translator session is prepared for the same source.
# ---------------------------------------------------------------------------
insert_before = '''    /** Playback-time lookup only. Translation runs only in the prefetch worker. */
    suspend fun updatePlaybackPosition(positionMs: Long) {
'''
ensure_method = '''    /**
     * Idempotently prepares the translator for the subtitle that mpv is actually
     * using. This is intentionally safe to call from the PlayerScreen polling
     * loop: it only rebuilds the session when the source/provider really changed.
     */
    suspend fun ensurePrepared(
        path: String?,
        positionMs: Long,
        scope: CoroutineScope,
        providerId: String = "local",
    ) {
        val normalized = path
            ?.removePrefix("file://")
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.absolutePath
            ?: return
        val wantedProvider = providerId.trim().lowercase().ifBlank { "local" }
        val alreadyPrepared = lock.withLock {
            sourcePath == normalized &&
                activeProviderId == wantedProvider &&
                sourceContent != null && cues.isNotEmpty() && worker?.isActive == true
        }
        if (!alreadyPrepared) {
            Log.i(TAG, "SESSION_ENSURE_REPREPARE provider=$wantedProvider path=$normalized positionMs=$positionMs")
            prepare(normalized, positionMs, scope, wantedProvider)
        }
    }

    /** Playback-time lookup only. Translation runs only in the prefetch worker. */
    suspend fun updatePlaybackPosition(positionMs: Long) {
'''
replace_once(REALTIME, insert_before, ensure_method, "add idempotent late-source prepare")

replace_once(
    PLAYER,
    '''        if (subtitleTranslationMode == "korean") {
            realtimeTranslator.prepare(localSubtitle, engine.currentPosition, playerScope, vm.playerSettings.translationProvider)
            setSubtitleVisibleForMode(false)
''',
    '''        if (subtitleTranslationMode == "korean") {
            val source = engine.currentSubtitleFilePath ?: localSubtitle
            realtimeTranslator.prepare(source, engine.currentPosition, playerScope, vm.playerSettings.translationProvider)
            setSubtitleVisibleForMode(false)
''',
    "prepare actual active subtitle source",
)
replace_once(
    PLAYER,
    '''        while (isActive) {
            val source = engine.currentSubtitleFilePath ?: localSubtitle
            libassTranslationOverlay?.setSource(source)
''',
    '''        while (isActive) {
            val source = engine.currentSubtitleFilePath ?: localSubtitle
            realtimeTranslator.ensurePrepared(
                source,
                engine.currentPosition,
                playerScope,
                vm.playerSettings.translationProvider,
            )
            libassTranslationOverlay?.setSource(source)
''',
    "recover translator when subtitle source appears late",
)

print("Applied translated subtitle runtime fixes successfully")
