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
        val source = File(context.cacheDir, "pixel-source.srt").apply {
            writeText((0 until 12).joinToString("\n\n") { i -> "${i+1}\n00:00:%02d,000 --> 00:00:%02d,999\nLine $i".format(i,i) })
        }
        context.getSharedPreferences("lilac_offline_store", 0).edit().putInt("pref_ai_prefetch_ahead", 10).commit()
        val gate = CompletableDeferred<Unit>()
        val translator = RealtimeSubtitleTranslator(context, providerFactory = {
            object : TranslationProvider {
                override val id = "gemini"
                override val displayName = "pixel-test"
                override suspend fun translateBatch(lines: List<String>): List<String> { gate.await(); delay(300); return lines.map { "한국어 대사 $it" } }
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
            activity.setContentView(surface, FrameLayout.LayoutParams(-1,-1))
        }
        suspend fun frame(): Bitmap = withContext(Dispatchers.Main) { surface.bitmap ?: error("Texture has no video frame") }
        fun bright(bitmap: Bitmap): Int {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return pixels.count { (it shr 16 and 255)>170 && (it shr 8 and 255)>170 && (it and 255)>170 }
        }
        suspend fun waitPixels(visible: Boolean, name: String) {
            var captured: Bitmap? = null
            withTimeout(8000) {
                while (true) {
                    delay(100)
                    val b=frame()
                    val count=bright(b)
                    if ((visible && count>80) || (!visible && count<20)) { captured=b; break }
                    b.recycle()
                }
            }
            val dir=File(context.getExternalFilesDir(null),"subtitle-evidence").apply { mkdirs() }
            File(dir,"$name.png").outputStream().use { captured!!.compress(Bitmap.CompressFormat.PNG,100,it) }
            captured!!.recycle()
        }
        try {
            withTimeout(5000) { while (!surface.isAvailable) delay(50) }
            ins.runOnMainSync { engine.load(video.path, source.path,0,null) }
            withTimeout(8000) { while (engine.playbackState!=MpvPlayerEngine.STATE_READY || engine.videoWidth==0) delay(50) }
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
                        waitPixels(true,"translated-playing-$cue")
                        seen += cue
                    }
                    delay(50)
                }
            }
            assertEquals((2..7).toSet(), seen)
            ins.runOnMainSync { engine.pause(); engine.seekTo(1100) }
            waitPixels(true,"translated-rewind")
        } finally {
            translator.clear(); workers.cancel()
            ins.runOnMainSync { engine.release() }; scenario.close()
            video.delete(); source.delete(); projected.delete()
        }
    }
}
