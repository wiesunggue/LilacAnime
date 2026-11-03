package com.lilac.anime.player

import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lilac.anime.Anime
import com.lilac.anime.AppContextHolder
import com.lilac.anime.Episode
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.subtitle.SubtitleSelectionStore
import com.lilac.anime.data.subtitle.SubtitleStore
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import com.lilac.anime.data.subtitle.translation.RealtimeSubtitleTranslator
import com.lilac.anime.viewmodel.AnimeViewModel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real PlayerScreen buttons/effects, real mpv/TextureView and source file.
 * Only remote model latency/results are deterministic; no paid API is called. */
class PlayerSubtitleIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<SubtitleTestActivity>()

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) texture(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun frame(): Bitmap {
        var result: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = texture(compose.activity.window.decorView)?.bitmap
        }
        return requireNotNull(result) { "PlayerScreen has no rendered TextureView frame" }
    }
    private fun greenHeight(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var first = bitmap.height
        var last = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val colour = pixels[y * bitmap.width + x]
            val r = colour shr 16 and 255; val g = colour shr 8 and 255; val b = colour and 255
            if (g > 100 && g > r * 2 && g > b * 2) { first = minOf(first, y); last = maxOf(last, y) }
        }
        return if (last < first) 0 else last - first + 1
    }
    private fun capture(name: String): Int {
        val bitmap = frame()
        try {
            val height = greenHeight(bitmap)
            saveSubtitleEvidence(InstrumentationRegistry.getInstrumentation().targetContext, name, bitmap)
            return height
        } finally { bitmap.recycle() }
    }
    private data class RenderedFrame(val greenHeight: Int, val pixels: IntArray, val width: Int, val height: Int) {
        fun samePixels(other: RenderedFrame) = pixels.contentEquals(other.pixels)
    }
    private val deferredEvidence = linkedMapOf<String, RenderedFrame>()
    private fun flushEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((name, frame) in deferredEvidence) {
            val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            try { saveSubtitleEvidence(context, name, bitmap) } finally { bitmap.recycle() }
        }
        deferredEvidence.clear()
    }
    // sub-text changes before TextureView receives the corresponding video frame.
    // Paused checks require two identical rendered frames. Playback captures
    // require the native cue AND changed pixels, and defer PNG encoding so
    // sampling itself cannot consume the following cue's real-time interval.
    private suspend fun waitFrame(
        name: String,
        requireStable: Boolean = true,
        saveLater: Boolean = false,
        expected: (RenderedFrame) -> Boolean,
    ): RenderedFrame {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        var previous: RenderedFrame? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            // Compose tests control LaunchedEffect's clock. Native mpv keeps
            // playing in real time, so bitmap-only polling must also advance
            // the screen's translation publisher instead of freezing its delay.
            compose.mainClock.advanceTimeBy(100)
            val bitmap = frame()
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val current = RenderedFrame(greenHeight(bitmap), pixels, bitmap.width, bitmap.height)
                if (current.greenHeight > 0 && expected(current) && (!requireStable || previous?.samePixels(current) == true)) {
                    if (saveLater) deferredEvidence[name] = current
                    else saveSubtitleEvidence(InstrumentationRegistry.getInstrumentation().targetContext, name, bitmap)
                    android.util.Log.i("SubtitleFrameTest", "FRAME_MATCH name=$name greenHeight=${current.greenHeight}")
                    return current
                }
                previous = current
            } finally { bitmap.recycle() }
            delay(100)
        }
        capture("$name-frame-wait-failure")
        val engine = MpvPlaybackManager.currentEngineOrNull()
        throw AssertionError("Rendered frame did not satisfy $name; last green height=${previous?.greenHeight} " +
            "text=${engine?.getCurrentSubtitleText()} position=${engine?.currentPosition} track=${engine?.currentSubtitleFilePath}")
    }
    private fun waitText(text: String) {
        try {
            compose.waitUntil(60000) { MpvPlaybackManager.currentEngineOrNull()?.getCurrentSubtitleText()?.contains(text) == true }
        } catch (failure: Throwable) {
            val engine = MpvPlaybackManager.currentEngineOrNull()
            capture("screen-text-wait-failure")
            throw AssertionError("Expected=$text actual=${engine?.getCurrentSubtitleText()} position=${engine?.currentPosition} playing=${engine?.isPlaying} track=${engine?.currentSubtitleFilePath}", failure)
        }
    }
    private fun chooseMode(label: String) {
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label).assertIsSelected()
    }
    private fun settings() {
        if (compose.onAllNodesWithContentDescription("플레이어 설정").fetchSemanticsNodes().isEmpty()) {
            compose.onRoot().performTouchInput { click() }
        }
        compose.onNodeWithContentDescription("플레이어 설정").performClick()
    }

    @Test fun realScreenRetainsColourResizesDuringTranslationAndKeepsLaterCues() = runBlocking<Unit> {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        AppContextHolder.init(context)
        val animeId = "subtitle-ui-colour-size"
        val episode = Episode("1", 1, "자막 회귀 검사")
        val video = MpvOfflineStore.videoFile(context, animeId, episode.id).apply { parentFile!!.mkdirs() }
        ins.context.assets.open("player-black.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val source = File(context.cacheDir, "regression_ep_1_linkkf.srt").apply {
            writeText((0 until 8).joinToString("\n\n") { cue ->
                val start = cue * 3; val end = start + 3
                "${cue+1}\n00:00:%02d,000 --> 00:00:%02d,000\n<font color=\"#00ff00\"><b>Original $cue</b></font>".format(start, end)
            })
        }
        SubtitleStore.save(context, animeId, "1", 1, "linkkf", source.path)
        SubtitleSelectionStore.clear(context, animeId)
        val gate = CompletableDeferred<Unit>()
        val provider = object : TranslationProvider {
            override val id = "gemini"
            override val displayName = "deterministic delayed model"
            override suspend fun translateBatch(lines: List<String>): List<String> {
                gate.await(); delay(300)
                return lines.map { "한국어 대사 ${it.substringAfterLast(' ')}" }
            }
        }
        val vm = ViewModelProvider(compose.activity)[AnimeViewModel::class.java]
        compose.runOnUiThread {
            vm.updatePlayerSettings(context, vm.playerSettings.copy(translationProvider = "gemini", subtitleSourcePreference = "linkkf", subtitleSize = 100f, vttBold = true, autoPlay = false))
        }
        val anime = Anime(id = animeId, title = "자막 회귀 검사", source = "linkkf", episodes = listOf(episode))
        try {
            compose.setContent { MaterialTheme { PlayerScreen(anime, episode, vm, {}, { _, id -> assertEquals("gemini", id); provider }) } }
            compose.waitUntil(60000) { compose.onAllNodesWithText("이 자막으로 재생").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(60000) { compose.onAllNodesWithText("자막 검색 중… 언제든 취소할 수 있습니다.").fetchSemanticsNodes().isEmpty() }
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("이 자막으로 재생").performClick()
            compose.waitUntil(60000) { MpvPlaybackManager.currentEngineOrNull()?.playbackState == MpvPlayerEngine.STATE_READY }
            val engine = requireNotNull(MpvPlaybackManager.currentEngineOrNull())
            compose.runOnUiThread { engine.pause(); engine.seekTo(500) }
            waitText("Original 0")
            assertEquals("Translation must leave the original selected while the provider is pending", source.absolutePath, engine.currentSubtitleFilePath)
            val original = waitFrame("screen-pending-original-green") { true }
            gate.complete(Unit)
            waitText("한국어 대사 0")
            assertFalse("Late subtitle completion must not override pause", engine.isPlaying)
            assertTrue(engine.currentSubtitleFilePath!!.endsWith(".srt"))
            val small = waitFrame("screen-translated-green-100") { !it.samePixels(original) }
            assertTrue("The original file must not be overwritten", source.readText().contains("Original 0"))

            settings()
            compose.onNodeWithContentDescription("자막 크기").performScrollTo().assertIsDisplayed()
                .performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 20f, center.y)) }
            compose.runOnIdle { assertTrue("The real size slider must accept touch input", vm.playerSettings.subtitleSize > 160f) }
            val large = waitFrame("screen-translated-green-180") { it.greenHeight > small.greenHeight * 1.35 }
            chooseMode("원본 그대로")
            waitText("Original 0")
            assertEquals(source.absolutePath, engine.currentSubtitleFilePath)
            waitFrame("screen-original-after-toggle") { !it.samePixels(large) && it.greenHeight >= large.greenHeight * .8 }
            chooseMode("AI 한국어 번역")
            waitText("한국어 대사 0")
            waitFrame("screen-translated-after-toggle") { it.samePixels(large) }
            compose.onNodeWithContentDescription("닫기").performClick()

            val seen = mutableSetOf<Int>()
            var previousCue = large
            compose.runOnUiThread { engine.seekTo(1000); engine.setSpeed(1f); engine.play() }
            val deadline = android.os.SystemClock.elapsedRealtime() + 40000
            while (engine.currentPosition < 18100 && android.os.SystemClock.elapsedRealtime() < deadline) {
                compose.mainClock.advanceTimeBy(100)
                val position = engine.currentPosition
                val cue = (position / 3000).toInt()
                if (cue in 1..5 && position % 3000 in 400..1900 && cue !in seen) {
                    // Position and sub-text arrive independently. The frame wait
                    // below must observe this exact cue, rather than assuming
                    // the first native property read after a time-pos event is current.
                    previousCue = waitFrame("screen-playing-green-$cue", requireStable = false, saveLater = true) {
                        !it.samePixels(previousCue) && engine.getCurrentSubtitleText().contains("한국어 대사 $cue")
                    }
                    seen += cue
                }
                delay(100)
            }
            compose.runOnUiThread { engine.pause(); engine.seekTo(500) }
            waitText("한국어 대사 0")
            waitFrame("screen-translated-rewind") { it.samePixels(large) }
            assertEquals("Normal-speed real PlayerScreen must retain every later translation", (1..5).toSet(), seen)
        } finally {
            gate.complete(Unit)
            compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            flushEvidence()
            source.delete(); video.delete()
            SubtitleStore.delete(context, animeId, "1", 1, "linkkf")
            SubtitleSelectionStore.clear(context, animeId)
        }
    }
    @Test fun assSizeSliderScalesRealPixelsWithoutReplacingScriptColour() = runBlocking<Unit> {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val video = File(context.cacheDir, "ass-size-black.mp4")
        ins.context.assets.open("player-black.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val source = File(context.cacheDir, "ass-size-source.ass").apply { writeText("""[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Roboto,28,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,20,20,30,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:40.00,Default,,0,0,0,,{\c&H00FF00&}초록색 ASS 자막{\c&HFFFFFF&}
""") }
        lateinit var engine: MpvPlayerEngine
        compose.runOnUiThread {
            engine = MpvPlayerEngine(context)
            compose.activity.setContentView(MpvPlayerSurfaceView(compose.activity, engine), android.widget.FrameLayout.LayoutParams(640, 360))
        }
        try {
            compose.waitUntil(30000) { texture(compose.activity.window.decorView)?.isAvailable == true }
            compose.runOnUiThread { engine.load(video.path, source.path, 0, null, false) }
            compose.waitUntil(60000) { engine.playbackState == MpvPlayerEngine.STATE_READY && engine.videoWidth > 0 }
            compose.runOnUiThread {
                engine.pause(); engine.seekTo(500); engine.setSubtitleVisible(true)
                engine.applySubtitleStyle(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 100f, false, 1f, .1f, false)
            }
            compose.waitUntil(15000) { engine.getCurrentSubtitleText().contains("초록색") }
            val small = waitFrame("ass-green-100") { true }
            compose.runOnUiThread { engine.applySubtitleStyle(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 180f, false, 1f, .1f, false) }
            val large = waitFrame("ass-green-180") { it.greenHeight > small.greenHeight * 1.35 }
            val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val translator = RealtimeSubtitleTranslator(context, providerFactory = {
                object : TranslationProvider {
                    override val id = "gemini"
                    override val displayName = "fixture"
                    override suspend fun translateBatch(lines: List<String>) = lines.map { "번역된 ASS 자막" }
                }
            })
            val output = object : TranslatedSubtitleTrack.Output {
                override val selectedPath get() = engine.currentSubtitleFilePath
                override val ready get() = engine.playbackState == MpvPlayerEngine.STATE_READY
                override val mediaGeneration get() = engine.activeLoadGeneration
                override fun select(path: String): Boolean {
                    var selected = false
                    ins.runOnMainSync { selected = engine.replaceSubtitleTrack(path) }
                    return selected
                }
            }
            val translated = TranslatedSubtitleTrack(context.cacheDir, source.path, output)
            try {
                translator.ensurePrepared(source.path, 500, workers, "gemini")
                var snapshot: RealtimeSubtitleTranslator.PlaybackSnapshot? = null
                withTimeout(15000) { while (snapshot == null) { snapshot = translator.nativePlaybackSnapshot(); delay(50) } }
                translated.update(snapshot)
                compose.waitUntil(15000) { engine.getCurrentSubtitleText().contains("번역된 ASS") }
                val translatedFrame = waitFrame("ass-translated-green-180") { !it.samePixels(large) && it.greenHeight >= large.greenHeight * .75 }
                val selected = engine.currentSubtitleFilePath
                compose.runOnUiThread { assertFalse(engine.replaceSubtitleTrack("/nonexistent/corrupt.ass")) }
                assertEquals("Failed replacement must retain the translated subtitle", selected, engine.currentSubtitleFilePath)
                assertTrue(engine.getCurrentSubtitleText().contains("번역된 ASS"))
                waitFrame("ass-retained-after-failed-replacement") { it.samePixels(translatedFrame) }
            } finally {
                translated.close(); translator.clear(); workers.cancel()
            }
        } finally {
            compose.runOnUiThread { engine.release() }
            source.delete(); video.delete()
        }
    }

}
