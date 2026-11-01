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
class SubtitlePlaybackClockTest {
    @Test fun mpvPlaybackContinuesTranslatingWithTheOriginalTrackHidden() = runBlocking {
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
        val translator = RealtimeSubtitleTranslator(context, providerFactory = {
            object : TranslationProvider {
                override val id = "gemini"
                override val displayName = "clock test"
                override suspend fun translateBatch(lines: List<String>) = lines.map { "한국어 $it" }
            }
        })
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var engine: MpvPlayerEngine
        instrumentation.runOnMainSync {
            engine = MpvPlayerEngine(context)
            engine.load(wav.path, source.path, 0, null)
            engine.setSubtitleVisible(false)
        }
        try {
            val seen = mutableSetOf<Long>()
            withTimeout(20000) {
                while (seen.size < 6) {
                    engine.setSubtitleVisible(false)
                    val position = engine.currentPosition
                    val events = translator.playbackTick(source.path, position, workers, "gemini")
                    events.filter { position in it.startMs until it.endMs }.forEach { seen += it.startMs }
                    delay(25)
                }
            }
            assertEquals((0 until 6).map { it * 1000L }.toSet(), seen)
            assertTrue(engine.currentPosition >= 5000)
            instrumentation.runOnMainSync { engine.pause(); engine.seekTo(2000) }
            withTimeout(3000) { while (engine.currentPosition !in 1900..2200) delay(25) }
            val rewind = translator.playbackTick(source.path, engine.currentPosition, workers, "gemini")
            assertTrue(rewind.any { it.startMs == 2000L })
        } finally {
            translator.clear(); workers.cancel()
            instrumentation.runOnMainSync { engine.release() }
            wav.delete(); source.delete()
        }
    }
}
