package com.lilac.anime.player

import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lilac.anime.data.subtitle.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the production parser, translation worker, ASS packets and native libass pixels. */
@RunWith(AndroidJUnit4::class)
class SubtitleRenderingTest {
    @Test fun thirtyCuesRenderThenSeekAndClearWithoutNativeSubtitleEvents() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("lilac_offline_store", 0).edit().clear().putInt("pref_ai_prefetch_ahead", 0).commit()
        val source = File(context.cacheDir, "render-functional.srt").apply {
            writeText((0 until 30).joinToString("\n\n") { i ->
                "${i+1}\n00:00:%02d,000 --> 00:00:%02d,900\nOriginal $i".format(i * 2, i * 2)
            })
        }
        val provider = object : TranslationProvider {
            override val id = "gemini"
            override val displayName = "deterministic test provider"
            override suspend fun translateBatch(lines: List<String>) = lines.map { "한국어 대사 " + it.substringAfterLast(' ') }
        }
        val translator = RealtimeSubtitleTranslator(context, providerFactory = { provider })
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var overlay: LibassTranslationOverlay
        val scenario = ActivityScenario.launch(SubtitleTestActivity::class.java)
        scenario.onActivity { activity ->
            overlay = LibassTranslationOverlay(activity)
            activity.setContentView(overlay)
            overlay.layout(0, 0, 960, 540)
            overlay.setSource(source.path)
            assertTrue("Native libass initialization failed", overlay.isReady())
        }
        try {
            for (i in (0 until 30).toList() + listOf(0, 15, 29)) {
                val position = i * 2000L + 100
                translator.playbackTick(source.path, position, workers, "gemini")
                val events = try { withTimeout(5000) {
                    var ready = translator.consumeReadyTranslatedEvents()
                    while (ready.none { position in it.startMs until it.endMs }) {
                        delay(25)
                        ready = translator.consumeReadyTranslatedEvents()
                    }
                    ready
                } } catch (error: Exception) {
                    throw AssertionError("Timed out at cue $i / position $position; completed=${translator.consumeReadyTranslatedEvents().map { it.startMs }}", error)
                }
                instrumentation.runOnMainSync {
                    events.forEach { overlay.addTranslatedEvent(it.assEventData, it.startMs, it.endMs) }
                    overlay.sync(position, false)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue("No translated event at $position", overlay.hasActiveTranslatedEvent(position))
                    val bitmap = (overlay.drawable as? BitmapDrawable)?.bitmap
                    assertNotNull("No rendered Korean pixels for cue $i", bitmap)
                    val pixels = IntArray(bitmap!!.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Blank rendered cue $i", pixels.any { it ushr 24 != 0 })
                }
            }
            instrumentation.runOnMainSync { overlay.clear(); assertNull(overlay.drawable); assertFalse(overlay.isReady()) }
        } finally {
            translator.clear(); workers.cancel()
            instrumentation.runOnMainSync { overlay.clear() }
            scenario.close()
            source.delete()
        }
    }
}
