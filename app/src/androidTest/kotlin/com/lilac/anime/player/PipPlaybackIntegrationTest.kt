package com.lilac.anime.player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lilac.anime.Anime
import com.lilac.anime.AppContextHolder
import com.lilac.anime.Episode
import com.lilac.anime.MainActivity
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.subtitle.SubtitleSelectionStore
import com.lilac.anime.data.subtitle.SubtitleStore
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import com.lilac.anime.viewmodel.AnimeViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real MainActivity callbacks, HOME/PiP/task restoration and native video. */
class PipPlaybackIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) texture(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun expandPipFromSystemControls() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = Rect()
        val window = automation.windows.firstOrNull { it.isInPictureInPictureMode }
        if (window != null) window.getBoundsInScreen(bounds) else {
            // API 29 can omit the PiP flag from AccessibilityWindowInfo even
            // while Activity/WindowManager report a pinned activity. Read its
            // actual WM bounds; still expand through Android's own menu button.
            val dump = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys activity activities"))
                .bufferedReader().use { it.readText() }
            val pinned = dump.substringAfter("mode=pinned", "").substringBefore("\n  Stack #")
            val match = Regex("(?:mBounds=|bounds=)Rect\\((-?\\d+),\\s*(-?\\d+) - (-?\\d+),\\s*(-?\\d+)\\)")
                .find(pinned) ?: error("WindowManager did not report pinned bounds")
            bounds.set(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4].toInt())
        }
        android.util.Log.i("PipLifecycleTest", "PINNED_BOUNDS=$bounds")
        var lastTap = 0L
        compose.waitUntil(30000) {
            // On Android 10 the expand icon is visual only: its surrounding
            // menu consumes touches and expands PiP. Successful expansion does
            // not have to return true from an Accessibility ACTION_CLICK.
            if (!MainActivity.isInPictureInPicture) return@waitUntil true
            var expanded = false
            val roots = automation.windows.mapNotNull { it.root }.toMutableList()
            automation.rootInActiveWindow?.let { roots.add(it) }
            for (root in roots) {
                try {
                    for (id in listOf("expand_button", "pip_expand_button")) {
                        val buttons = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id")
                        for (button in buttons) {
                            try { if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) expanded = true }
                            finally { button.recycle() }
                        }
                    }
                } finally { root.recycle() }
            }
            if (!expanded && SystemClock.uptimeMillis() - lastTap > 1500) {
                lastTap = SystemClock.uptimeMillis()
                val y = bounds.top + bounds.height() * .25f
                val down = MotionEvent.obtain(lastTap, lastTap, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), y, 0)
                val up = MotionEvent.obtain(lastTap, lastTap + 40, MotionEvent.ACTION_UP, bounds.exactCenterX(), y, 0)
                down.source = InputDevice.SOURCE_TOUCHSCREEN
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                try { automation.injectInputEvent(down, true); automation.injectInputEvent(up, true) }
                finally { down.recycle(); up.recycle() }
            }
            expanded || !MainActivity.isInPictureInPicture
        }
    }

    @Test fun homeAndRestoreKeepsTheSamePlayingEngineAndTranslatedPixels() = runBlocking<Unit> {
        runPlayerScenario(systemNavigation = true)
    }

    @Test fun windowModeChangesDoNotReleaseTheMountedPlayer() = runBlocking<Unit> {
        runPlayerScenario(systemNavigation = false)
    }

    private suspend fun runPlayerScenario(systemNavigation: Boolean) {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        AppContextHolder.init(context)
        android.util.Log.i("PipLifecycleTest", "TARGET_VERSION=${context.packageManager.getPackageInfo(context.packageName, 0).versionCode} systemNavigation=$systemNavigation")
        ins.uiAutomation.serviceInfo = ins.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val animeId = "pip-restore-regression"
        val episode = Episode("1", 1, "PiP 복귀 검사")
        val anime = Anime(id = animeId, title = "PiP 복귀 검사", source = "linkkf", episodes = listOf(episode))
        val video = MpvOfflineStore.videoFile(context, animeId, episode.id).apply { parentFile!!.mkdirs() }
        ins.context.assets.open("pip-black.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val source = File(context.cacheDir, "pip_ep_1_linkkf.srt").apply {
            writeText("1\n00:00:00,000 --> 00:02:59,000\n<font color=\"#00ff00\">Original PiP caption</font>")
        }
        SubtitleStore.save(context, animeId, "1", 1, "linkkf", source.path)
        SubtitleSelectionStore.clear(context, animeId)
        val provider = object : TranslationProvider {
            override val id = "gemini"
            override val displayName = "PiP fixture"
            override suspend fun translateBatch(lines: List<String>) = lines.map { "작은 화면에서 돌아와도 유지되는 한국어 자막" }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        try {
            scenario.onActivity { host ->
                activity = host
                val vm = ViewModelProvider(host)[AnimeViewModel::class.java]
                vm.updatePlayerSettings(context, vm.playerSettings.copy(translationProvider = "gemini", subtitleSourcePreference = "linkkf", autoPlay = false))
                host.setContent { MaterialTheme { PlayerScreen(anime, episode, vm, {}, { _, _ -> provider }) } }
            }
            compose.waitUntil(60000) { compose.onAllNodesWithText("이 자막으로 재생").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(60000) { compose.onAllNodesWithText("자막 검색 중… 언제든 취소할 수 있습니다.").fetchSemanticsNodes().isEmpty() }
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("이 자막으로 재생").performClick()
            val engine = requireNotNull(MpvPlaybackManager.currentEngineOrNull())
            compose.waitUntil(60000) { engine.playbackState == MpvPlayerEngine.STATE_READY && engine.getCurrentSubtitleText().contains("한국어") }
            ins.runOnMainSync { engine.play(); MainActivity.isVideoPlaying = true }
            val startedAt = engine.currentPosition
            compose.waitUntil(15000) { engine.isPlaying && engine.currentPosition > startedAt + 200 }
            val generation = engine.activeLoadGeneration
            var fullWidth = 0
            ins.runOnMainSync { fullWidth = activity.window.decorView.width }
            repeat(3) { cycle ->
                val before = engine.currentPosition
                if (systemNavigation) {
                    assertTrue(ins.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                    compose.waitUntil(30000) { activity.isInPictureInPictureMode && MainActivity.isInPictureInPicture }
                } else {
                    ins.runOnMainSync { MainActivity.isInPictureInPicture = true }
                    compose.waitForIdle()
                }
                assertSame("HOME must keep the playing engine", engine, MpvPlaybackManager.currentEngineOrNull())
                android.util.Log.i("PipLifecycleTest", "PIP_ENTER cycle=$cycle systemNavigation=$systemNavigation")
                if (systemNavigation) {
                    expandPipFromSystemControls()
                    compose.waitUntil(30000) { !activity.isInPictureInPictureMode && !MainActivity.isInPictureInPicture }
                } else ins.runOnMainSync { MainActivity.isInPictureInPicture = false }
                compose.waitForIdle()
                assertSame("PiP restore $cycle released the live native player", engine, MpvPlaybackManager.currentEngineOrNull())
                assertEquals("PiP must not reload the episode", generation, engine.activeLoadGeneration)
                assertTrue("PiP must preserve playing state: cycle=$cycle position=${engine.currentPosition} state=${engine.playbackState} action=${engine.playbackActionRevision} track=${engine.currentSubtitleFilePath}", engine.isPlaying)
                assertTrue("PiP must not reset playback", engine.currentPosition >= before)
                compose.waitUntil(15000) {
                    var green = 0
                    var restoredWidth = 0
                    ins.runOnMainSync {
                        restoredWidth = activity.window.decorView.width
                        texture(activity.window.decorView)?.bitmap?.let { frame ->
                            try {
                                val pixels = IntArray(frame.width * frame.height)
                                frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                                green = pixels.count { (it shr 8 and 255) > 100 && (it shr 8 and 255) > (it shr 16 and 255) * 2 && (it shr 8 and 255) > (it and 255) * 2 }
                                if (green > 80 && restoredWidth >= fullWidth * .8) saveSubtitleEvidence(context, "pip-restored-$cycle", frame)
                            } finally { frame.recycle() }
                        }
                    }
                    green > 80 && restoredWidth >= fullWidth * .8 && engine.getCurrentSubtitleText().contains("한국어")
                }
                android.util.Log.i("PipLifecycleTest", "RESTORED cycle=$cycle generation=${engine.activeLoadGeneration} position=${engine.currentPosition}")
            }
        } finally {
            scenario.close()
            MainActivity.isVideoPlaying = false
            MainActivity.isInPictureInPicture = false
            source.delete(); video.delete()
            SubtitleStore.delete(context, animeId, "1", 1, "linkkf")
            SubtitleSelectionStore.clear(context, animeId)
        }
    }
}
