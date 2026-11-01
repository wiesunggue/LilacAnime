package com.lilac.anime.player

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilac.anime.data.subtitle.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual mpv media clock; no collection of sub-text/subtitleEvents. */
@RunWith(AndroidJUnit4::class)
class NativeTranslationTrackTest {
    @Test fun nativeTrackDisplaysPendingSourceThenTranslationAndRewind() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("lilac_offline_store", 0).edit().clear().putInt("pref_ai_prefetch_ahead", 0).commit()
        val samples = 8000 * 8
        val wav = File(context.cacheDir, "subtitle-clock.wav")
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
        wav.outputStream().use { it.write(header); it.write(ByteArray(samples * 2)) }
        val source = File(context.cacheDir, "subtitle-clock.srt").apply {
            writeText((0 until 6).joinToString("\n\n") { i ->
                "${i+1}\n00:00:0$i,000 --> 00:00:0$i,900\nLine $i"
            })
        }
        val gate = CompletableDeferred<Unit>()
        val translator = RealtimeSubtitleTranslator(context, providerFactory = {
            object : TranslationProvider {
                override val id = "gemini"
                override val displayName = "clock test"
                override suspend fun translateBatch(lines: List<String>): List<String> { gate.await(); return lines.map { "한국어 $it" } }
            }
        })
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var engine: MpvPlayerEngine
        instrumentation.runOnMainSync {
            engine = MpvPlayerEngine(context)
            engine.load(wav.path, source.path, 0, null)
            engine.setSubtitleVisible(true)
        }
        val projected = File(context.cacheDir, "native-projection.ass")
        try {
            withTimeout(5000) { while (engine.playbackState != MpvPlayerEngine.STATE_READY) delay(25) }
            instrumentation.runOnMainSync { engine.pause(); engine.seekTo(100) }
            translator.playbackTick(source.path, 100, workers, "gemini")
            projected.writeText(translator.nativeTrackSnapshot()!!)
            instrumentation.runOnMainSync { engine.replaceSubtitleTrack(projected.path); engine.setSubtitleVisible(true) }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("Line 0")) delay(25) }
            assertFalse(engine.getCurrentSubtitleText().contains("한국어"))
            gate.complete(Unit)
            withTimeout(5000) { while (translator.consumeReadyTranslatedEvents().isEmpty()) delay(25) }
            projected.writeText(translator.nativeTrackSnapshot()!!)
            instrumentation.runOnMainSync { engine.reloadCurrentSubtitle() }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("한국어")) delay(25) }
            instrumentation.runOnMainSync { engine.seekTo(4100) }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("Line 4")) delay(25) }
            assertFalse(engine.getCurrentSubtitleText().contains("한국어"))
            translator.playbackTick(source.path, 4100, workers, "gemini")
            withTimeout(5000) { while (translator.consumeReadyTranslatedEvents().none { it.startMs == 4000L }) delay(25) }
            projected.writeText(translator.nativeTrackSnapshot()!!)
            instrumentation.runOnMainSync { engine.reloadCurrentSubtitle() }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("한국어 Line 4")) delay(25) }
            instrumentation.runOnMainSync { engine.seekTo(100) }
            withTimeout(5000) { while (!engine.getCurrentSubtitleText().contains("한국어 Line 0")) delay(25) }
        } finally {
            translator.clear(); workers.cancel()
            instrumentation.runOnMainSync { engine.release() }
            wav.delete(); source.delete(); projected.delete()
        }
    }
}
