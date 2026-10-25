package com.lilac.anime.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The same discrete speeds offered by the desktop player. */
internal object PlaybackControls {
    val speeds = listOf(.1f, .25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
    fun normalizeSpeed(value: Float): Float = if (!value.isFinite()) 1f else speeds.minBy { kotlin.math.abs(it - value) }
    fun stepSpeed(value: Float, direction: Int): Float = speeds[(speeds.indexOf(normalizeSpeed(value)) + direction).coerceIn(speeds.indices)]
    fun completedPosition(position: Long, duration: Long): Boolean = duration > 0L && position >= (duration - 2_000L).coerceAtLeast(duration / 2)
    fun percentPosition(duration: Long, digit: Int): Long? =
        if (duration <= 0L || digit !in 0..9) null else (duration * (digit / 10.0)).toLong()
}

/** Space: short tap toggles playback, hold boosts temporarily; key repeats are ignored. */
internal class HoldToBoostController(
    private val scope: CoroutineScope,
    private val isPlaying: () -> Boolean,
    private val toggle: () -> Unit,
    private val boost: (Boolean) -> Unit
) {
    private var pressed = false
    private var active = false
    private var pending: Job? = null
    fun down() {
        if (pressed) return
        pressed = true
        pending = scope.launch {
            delay(450L)
            if (pressed && isPlaying()) { active = true; boost(true) }
        }
    }
    fun up(cancelled: Boolean = false) {
        if (!pressed) return
        pressed = false
        pending?.cancel()
        pending = null
        val wasActive = active
        active = false
        if (wasActive) boost(false) else if (!cancelled) toggle()
    }
}
