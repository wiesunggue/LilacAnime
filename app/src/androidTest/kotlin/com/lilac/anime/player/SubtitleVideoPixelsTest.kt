package com.lilac.anime.player

import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilac.anime.data.subtitle.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual mpv TextureView pixels over a black video, not just sub-text. */
@RunWith(AndroidJUnit4::class)
class SubtitleVideoPixelsTest {
    @Test fun translatedPixelsSurvivePlaybackSeekAndVisibilityToggle() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        val video = File(context.cacheDir, "audit-black.mp4")
        ins.context.assets.open("black.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        // At 640x360, the original short "Line 1" fixture rendered only 43
        // bright pixels despite being visible. Use a complete source sentence;
        // retain the >80 visible / <20 hidden criteria and every expected cue.
        val source = File(context.cacheDir, "pixel-source.srt").apply {
            writeText((0 until 12).joinToString("\n\n") { i -> "${i+1}\n00:00:%02d,000 --> 00:00:%02d,999\nSource subtitle Line $i".format(i,i) })
        }
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 10).commit()
        val gate = CompletableDeferred<Unit>()
        val translator = RealtimeSubtitleTranslator(context, providerFactory = {
            object : TranslationProvider {
                override val id = "gemini"
                override val displayName = "pixel-test"
                override suspend fun translateBatch(lines: List<String>): List<String> { gate.await(); delay(300); return lines.map { "한국어 대사 ${it.removePrefix("Source subtitle ")}" } }
            }
        })
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val projected = File(context.cacheDir, "pixel-track.ass")
        lateinit var engine: MpvPlayerEngine
        lateinit var surface: MpvPlayerSurfaceView
        val scenario = ActivityScenario.launch(SubtitleTestActivity::class.java)
        scenario.onActivity { activity ->
            engine = MpvPlayerEngine(activity)
            surface = MpvPlayerSurfaceView(activity, engine)
            activity.setContentView(surface, FrameLayout.LayoutParams(640,360))
        }
        suspend fun frame(): Bitmap = withContext(Dispatchers.Main) { surface.bitmap ?: error("Texture has no video frame") }
        fun bright(bitmap: Bitmap): Int {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.count { (it shr 16 and 255)>170 && (it shr 8 and 255)>170 && (it and 255)>170 }
        }
        val evidence = linkedMapOf<String, Bitmap>()
        suspend fun waitPixels(visible: Boolean, name: String) {
            var last: Bitmap? = null
            var count = -1
            val matched = withTimeoutOrNull(8000) {
                while (true) {
                    delay(100)
                    last?.recycle()
                    last = frame()
                    count = bright(last!!)
                    if ((visible && count > 80) || (!visible && count < 20)) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true
            last?.let { evidence[name] = it }
            assertTrue(
                "Pixel stage=$name visible=$visible bright=$count position=${engine.currentPosition} " +
                    "playing=${engine.isPlaying} text=${engine.getCurrentSubtitleText()} track=${engine.currentSubtitleFilePath}",
                matched,
            )
        }
        try {
            withTimeout(5000) { while (!surface.isAvailable) delay(50) }
            ins.runOnMainSync { engine.load(video.path, source.path,0,null) }
            // Cold libass/fontconfig setup took ~14s on the software emulator.
            // This is media startup, not a subtitle visibility assertion; retain
            // the separate 8s pixel deadlines after the player becomes ready.
            withTimeout(60000) { while (engine.playbackState!=MpvPlayerEngine.STATE_READY || engine.videoWidth==0) delay(50) }
            ins.runOnMainSync { engine.pause(); engine.seekTo(1100) }
            translator.ensurePrepared(source.path,1100,workers,"gemini")
            projected.writeText(translator.nativeTrackSnapshot()!!)
            ins.runOnMainSync { engine.replaceSubtitleTrack(projected.path); engine.setSubtitleVisible(true) }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("Line 1")) delay(50) }
            waitPixels(true,"pending-source")
            gate.complete(Unit)
            withTimeout(5000) { while (translator.consumeReadyTranslatedEvents().none { it.startMs==1000L }) delay(50) }
            projected.writeText(translator.nativeTrackSnapshot()!!)
            ins.runOnMainSync { engine.replaceSubtitleTrack(projected.path); engine.setSubtitleVisible(true) }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("한국어")) delay(50) }
            waitPixels(true,"translated-paused")
            ins.runOnMainSync { engine.setSubtitleVisible(false) }
            waitPixels(false,"hidden")
            // sub-text still has Korean while hidden: explicitly prove that text alone is insufficient.
            assertTrue(engine.getCurrentSubtitleText().contains("한국어"))
            ins.runOnMainSync { engine.setSubtitleVisible(true); engine.setSpeed(2f); engine.play() }
            val seen = mutableSetOf<Int>()
            val captureTimings = mutableListOf<String>()
            withTimeout(10000) {
                while (engine.currentPosition < 8100) {
                    val position = engine.currentPosition
                    translator.updatePlaybackPosition(position)
                    translator.nativeTrackSnapshot()?.let { snapshot ->
                        projected.writeText(snapshot)
                        ins.runOnMainSync { engine.reloadCurrentSubtitle() }
                    }
                    val cue = (position / 1000).toInt()
                    if (cue in 2..7 && position % 1000 in 250..750 && cue !in seen) {
                        assertTrue("Missing translated cue $cue at $position", engine.getCurrentSubtitleText().contains("한국어 대사 Line $cue"))
                        val started = android.os.SystemClock.elapsedRealtime()
                        waitPixels(true,"translated-playing-$cue")
                        captureTimings += "cue=$cue position=$position->${engine.currentPosition} captureMs=${android.os.SystemClock.elapsedRealtime() - started}"
                        seen += cue
                    }
                    delay(50)
                }
            }
            ins.runOnMainSync { engine.pause(); engine.seekTo(1100) }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("한국어 대사 Line 1")) delay(50) }
            waitPixels(true,"translated-rewind")
            assertEquals("Playback capture timings: $captureTimings", (2..7).toSet(), seen)
        } finally {
            // Encoding/writing PNGs during 2x playback can skip the next cue's
            // sampling window. Retain the actual frames and persist after pause.
            ins.runOnMainSync { engine.pause() }
            try {
                for ((name, bitmap) in evidence) saveSubtitleEvidence(context, name, bitmap)
            } finally {
                evidence.values.forEach { it.recycle() }
                translator.clear(); workers.cancel()
                ins.runOnMainSync { engine.release() }; scenario.close()
                video.delete(); source.delete(); projected.delete()
            }
        }
    }
}
