package com.lilac.anime.player

/** Owns automatic pauses only; user pause/seek and episode changes revoke them. */
internal class SubtitleTranslationBuffer(private val output: Output) {
    interface Output {
        val generation: Long
        val actionRevision: Long
        val playing: Boolean
        fun pause()
        fun playIfUnchanged(revision: Long): Boolean
    }
    private data class Pause(val generation: Long, val revision: Long)
    private var pause: Pause? = null

    fun update(waiting: Boolean) {
        pause?.let { if (it.generation != output.generation || it.revision != output.actionRevision) pause = null }
        if (waiting) {
            if (output.playing) {
                output.pause()
                pause = Pause(output.generation, output.actionRevision)
            }
        } else resumeOwnedPause()
    }

    fun close() = resumeOwnedPause()

    private fun resumeOwnedPause() {
        val owned = pause ?: return
        pause = null
        if (owned.generation == output.generation) output.playIfUnchanged(owned.revision)
    }
}
