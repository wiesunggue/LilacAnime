package com.lilac.anime.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageView
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import java.io.File
import java.lang.ref.WeakReference
import java.nio.charset.Charset
import kotlin.math.max

/**
 * In-memory libass overlay backed directly by peerless2012's ass-kt wrapper.
 *
 * The original ASS/SSA file is used only to initialize Script Info/Styles.
 * Translated events are appended one-by-one with AssTrack.readChunk(), so no
 * translated subtitle file is created and mpv never reloads its subtitle track.
 */
class LibassTranslationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    companion object {
        private const val TAG = "LibassTranslationOverlay"
        private const val FRAME_INTERVAL_MS = 33L
        private const val PREF_NAME = "lilac_offline_store"
        private const val PREF_SUBTITLE_SIZE = "pref_subtitle_size"

        @Volatile
        private var activeOverlay: WeakReference<LibassTranslationOverlay>? = null

        /**
         * Translation mode is controlled by PlayerScreen/RealtimeSubtitleTranslator,
         * while this AndroidView lives independently. Clearing through the active
         * overlay prevents old translated events from remaining visible after the
         * user returns to the original subtitle mode or changes subtitle sessions.
         */
        fun clearActiveTranslatedOverlay() {
            activeOverlay?.get()?.let { view ->
                view.post { view.clear() }
            }
        }
    }

    private val playerPrefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private var ass: Ass? = null
    private var track: AssTrack? = null
    private var renderer: AssRender? = null
    private var bitmap: Bitmap? = null
    private var sourcePath: String? = null
    private var widthPx = 0
    private var heightPx = 0
    private var playing = false
    private var positionMs = 0L
    private var syncWallClockMs = 0L
    private val events = mutableListOf<EventRange>()
    private val eventKeys = mutableSetOf<String>()
    private var renderPosted = false
    private var hasRenderedFrame = false
    private var nextReadOrder = 0
    private var videoAspectRatio = 0.0
    private var videoLeft = 0
    private var videoTop = 0
    private var videoWidthPx = 0
    private var videoHeightPx = 0
    private var subtitleScale = 1.0f

    private val renderRunnable = Runnable {
        renderPosted = false
        renderFrame()
    }

    private data class EventRange(val startMs: Long, val endMs: Long)

    init {
        activeOverlay = WeakReference(this)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ScaleType.FIT_XY
        isClickable = false
        isFocusable = false
    }

    /**
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

    private fun applySubtitleScale(scale: Float, force: Boolean) {
        if (!force && kotlin.math.abs(subtitleScale - scale) < 0.001f) return
        subtitleScale = scale
        renderer?.let { render ->
            runCatching { render.setFontScale(scale) }
                .onFailure { Log.e(TAG, "LIBASS_SET_FONT_SCALE_FAILED scale=$scale", it) }
        }
        if (isReady()) scheduleRender()
    }

    fun setSource(path: String?) {
        val normalized = path
            ?.removePrefix("file://")
            ?.takeIf { File(it).isFile }
            ?.let { File(it).absolutePath }
        if (normalized == sourcePath && isReady()) return

        releaseLibass()
        sourcePath = normalized
        events.clear()
        eventKeys.clear()
        hasRenderedFrame = false
        nextReadOrder = 0
        setImageDrawable(null)

        if (normalized == null) return

        val file = File(normalized)
        val content = readSubtitleText(file) ?: run {
            Log.e(TAG, "READ_SOURCE_FAILED path=${file.absolutePath}")
            sourcePath = null
            return
        }

        val header = buildCodecPrivate(content, file.extension.lowercase())
        ensureBitmap()

        runCatching {
            val a = Ass()
            val t = a.createTrack()
            val r = a.createRender()

            // Parse Script Info / Styles / Events Format without importing the
            // original Dialogue lines.
            t.readBuffer(header.toByteArray(Charsets.UTF_8))

            updateVideoViewport()
            val w = max(1, videoWidthPx)
            val h = max(1, videoHeightPx)
            r.setStorageSize(w, h)
            r.setFrameSize(w, h)
            subtitleScale = persistedSubtitleScale()
            r.setFontScale(subtitleScale)

            ass = a
            track = t
            renderer = r
            Log.i(TAG, "LIBASS_SOURCE_READY path=${file.name} scale=$subtitleScale size=${w}x$h")
        }.onFailure {
            Log.e(TAG, "LIBASS_CREATE_FAILED", it)
            releaseLibass()
            sourcePath = null
        }
    }

    /** Adds exactly one translated event to the in-memory libass track. */
    fun addTranslatedEvent(eventData: String, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        val dedupeKey = "$startMs:$endMs:${eventData.hashCode()}"
        if (dedupeKey in eventKeys) return

        val t = track ?: return
        eventKeys += dedupeKey
        runCatching {
            val payload = toMatroskaEventPacket(eventData, nextReadOrder++)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            Log.i(TAG, "LIBASS_ADD_EVENT ro=${nextReadOrder - 1} start=$startMs end=$endMs payload=${payload.take(160)}")
            t.readChunk(startMs.coerceAtLeast(0L), endMs - startMs, bytes)
            events.add(EventRange(startMs, endMs))
            scheduleRender()
        }.onFailure {
            eventKeys.remove(dedupeKey)
            Log.e(TAG, "LIBASS_ADD_EVENT_FAILED start=$startMs end=$endMs", it)
        }
    }

    fun isReady(): Boolean = track != null && renderer != null

    fun hasActiveTranslatedEvent(positionMs: Long): Boolean =
        events.any { positionMs >= it.startMs && positionMs < it.endMs }

    /** Sets the actual displayed video aspect ratio. */
    fun setVideoAspectRatio(aspectRatio: Double) {
        val normalized = aspectRatio.takeIf { it.isFinite() && it > 0.01 } ?: return
        if (kotlin.math.abs(videoAspectRatio - normalized) < 0.001) return
        videoAspectRatio = normalized
        updateVideoViewport()
        ensureBitmap()
        if (renderer != null && videoWidthPx > 0 && videoHeightPx > 0) {
            runCatching {
                renderer?.setStorageSize(videoWidthPx, videoHeightPx)
                renderer?.setFrameSize(videoWidthPx, videoHeightPx)
            }.onFailure { Log.e(TAG, "LIBASS_SET_VIDEO_VIEWPORT_FAILED", it) }
        }
        if (isReady()) scheduleRender()
    }

    fun sync(positionMs: Long, playing: Boolean) {
        this.positionMs = positionMs.coerceAtLeast(0L)
        this.syncWallClockMs = SystemClock.elapsedRealtime()
        this.playing = playing
        if (isReady()) scheduleRender()
    }

    fun clear() {
        releaseLibass()
        sourcePath = null
        events.clear()
        eventKeys.clear()
        hasRenderedFrame = false
        nextReadOrder = 0
        setImageDrawable(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        widthPx = w
        heightPx = h
        updateVideoViewport()
        ensureBitmap()
        if (renderer != null && videoWidthPx > 0 && videoHeightPx > 0) {
            runCatching {
                renderer?.setStorageSize(videoWidthPx, videoHeightPx)
                renderer?.setFrameSize(videoWidthPx, videoHeightPx)
                renderer?.setFontScale(subtitleScale)
            }.onFailure { Log.e(TAG, "LIBASS_SET_SIZE_FAILED", it) }
        }
    }

    private fun updateVideoViewport() {
        if (widthPx <= 0 || heightPx <= 0) {
            videoLeft = 0
            videoTop = 0
            videoWidthPx = max(1, widthPx)
            videoHeightPx = max(1, heightPx)
            return
        }

        val aspect = videoAspectRatio
        if (!aspect.isFinite() || aspect <= 0.01) {
            videoLeft = 0
            videoTop = 0
            videoWidthPx = widthPx
            videoHeightPx = heightPx
            return
        }

        val viewAspect = widthPx.toDouble() / heightPx.toDouble()
        if (viewAspect > aspect) {
            videoHeightPx = heightPx
            videoWidthPx = (heightPx * aspect).toInt().coerceIn(1, widthPx)
            videoLeft = (widthPx - videoWidthPx) / 2
            videoTop = 0
        } else {
            videoWidthPx = widthPx
            videoHeightPx = (widthPx / aspect).toInt().coerceIn(1, heightPx)
            videoLeft = 0
            videoTop = (heightPx - videoHeightPx) / 2
        }

        Log.d(TAG, "VIDEO_VIEWPORT view=${widthPx}x${heightPx} aspect=$aspect rect=${videoLeft},${videoTop} ${videoWidthPx}x${videoHeightPx}")
    }

    private fun ensureBitmap() {
        if (widthPx <= 0 || heightPx <= 0) return
        val current = bitmap
        if (current?.width == widthPx && current.height == heightPx && !current.isRecycled) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    }

    private fun scheduleRender() {
        if (renderPosted) return
        renderPosted = true
        postDelayed(renderRunnable, if (playing) FRAME_INTERVAL_MS else 0L)
    }

    private fun renderFrame() {
        val r = renderer ?: return
        val t = track ?: return
        refreshPersistedSubtitleScale()
        ensureBitmap()
        val target = bitmap ?: return
        if (target.isRecycled) return

        val renderPosition = if (playing && syncWallClockMs > 0L) {
            positionMs + (SystemClock.elapsedRealtime() - syncWallClockMs).coerceAtLeast(0L)
        } else {
            positionMs
        }

        val activeEvent = events.firstOrNull { renderPosition >= it.startMs && renderPosition < it.endMs }
        val active = activeEvent != null
        if (activeEvent != null) {
            Log.d(TAG, "LIBASS_ACTIVE time=$renderPosition start=${activeEvent.startMs} end=${activeEvent.endMs}")
        }
        if (!active) {
            if (hasRenderedFrame) {
                target.eraseColor(Color.TRANSPARENT)
                setImageDrawable(null)
                hasRenderedFrame = false
            }
            if (playing) scheduleRender()
            return
        }

        val frame = runCatching {
            updateVideoViewport()
            r.setFrameSize(max(1, videoWidthPx), max(1, videoHeightPx))
            r.setStorageSize(max(1, videoWidthPx), max(1, videoHeightPx))
            r.setTrack(t)
            r.renderFrame(renderPosition, AssTexType.BITMAP_RGBA)
        }.getOrElse {
            Log.e(TAG, "LIBASS_RENDER_FAILED", it)
            null
        }

        if (frame != null) {
            val images = frame.images.orEmpty().mapNotNull { tex ->
                val src = tex.bitmap
                if (src != null && !src.isRecycled && tex.w > 0 && tex.h > 0) {
                    Triple(src, tex.x, tex.y)
                } else null
            }
            val imageCount = images.size
            if (imageCount > 0) {
                val canvas = Canvas(target)
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                val saveCount = canvas.save()
                canvas.clipRect(
                    videoLeft,
                    videoTop,
                    videoLeft + videoWidthPx,
                    videoTop + videoHeightPx
                )
                images.forEach { (src, x, y) ->
                    canvas.drawBitmap(
                        src,
                        (videoLeft + x).toFloat(),
                        (videoTop + y).toFloat(),
                        null
                    )
                }
                canvas.restoreToCount(saveCount)
            }
            Log.i(TAG, "LIBASS_FRAME time=$renderPosition changed=${frame.changed} images=$imageCount active=$active scale=$subtitleScale")
            if (imageCount > 0) {
                setImageBitmap(target)
                hasRenderedFrame = true
            } else if (frame.changed != 0 && hasRenderedFrame) {
                target.eraseColor(Color.TRANSPARENT)
                setImageDrawable(null)
                hasRenderedFrame = false
            }
        }

        if (playing) scheduleRender()
    }

    private fun buildCodecPrivate(content: String, ext: String): String {
        if (ext != "ass" && ext != "ssa") {
            return """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Roboto,48,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,60,60,60,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
        }

        val normalized = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val out = StringBuilder()
        for (line in lines) {
            if (line.trimStart().startsWith("Dialogue:", true) ||
                line.trimStart().startsWith("Comment:", true)
            ) break
            out.append(line).append('\n')
        }
        if (!out.contains("[Events]")) out.append("\n[Events]\n")
        if (!Regex("(?im)^\\s*Format:\\s*Layer,\\s*Start").containsMatchIn(out)) {
            out.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        }
        return out.toString()
    }

    private fun toMatroskaEventPacket(eventData: String, readOrder: Int): String {
        val raw = eventData.trim()
            .removePrefix("Dialogue:")
            .trimStart()
        val fields = raw.split(',', limit = 10)
        if (fields.size >= 10) {
            return listOf(
                readOrder.toString(), fields[0], fields[3], fields[4],
                fields[5], fields[6], fields[7], fields[8], fields[9]
            ).joinToString(",")
        }
        return listOf(readOrder.toString(), "0", "Default", "", "0", "0", "0", "", raw)
            .joinToString(",")
    }

    private fun readSubtitleText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val candidates = mutableListOf<Charset>()
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> candidates += Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> candidates += Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> candidates += Charsets.UTF_16BE
            else -> candidates += listOf(Charsets.UTF_8, Charset.forName("Shift_JIS"), Charset.forName("EUC-KR"), Charsets.UTF_16LE, Charsets.UTF_16BE)
        }
        return candidates.distinct().mapNotNull { charset ->
            runCatching { String(bytes, charset).removePrefix("\uFEFF") }.getOrNull()
        }.maxByOrNull { decoded ->
            val replacement = decoded.count { it == '\uFFFD' }
            val controls = decoded.count { it.code in 0..8 || it.code in 14..31 }
            decoded.length - replacement * 20 - controls * 20
        }
    }

    private fun releaseLibass() {
        renderPosted = false
        removeCallbacks(renderRunnable)

        val r = renderer
        val t = track
        val a = ass
        renderer = null
        track = null
        ass = null

        runCatching { r?.release() }
        runCatching { t?.release() }
        runCatching { a?.release() }

        bitmap?.recycle()
        bitmap = null
    }

    override fun onDetachedFromWindow() {
        if (activeOverlay?.get() === this) activeOverlay = null
        clear()
        super.onDetachedFromWindow()
    }
}