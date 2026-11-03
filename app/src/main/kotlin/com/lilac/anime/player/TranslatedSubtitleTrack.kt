package com.lilac.anime.player

import com.lilac.anime.data.subtitle.translation.RealtimeSubtitleTranslator.PlaybackSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns only the generated track. The source file is never rewritten/deleted.
 * A fresh, complete file is selected before the previous track is removed. */
internal class TranslatedSubtitleTrack(
    private val directory: File,
    private val source: String,
    private val player: Output,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    interface Output {
        val selectedPath: String?
        val ready: Boolean
        val mediaGeneration: Long
        fun select(path: String): Boolean
    }

    private val owned = mutableSetOf<File>()
    private var latest: File? = null
    private var pending: PlaybackSnapshot? = null
    private var attachedGeneration: Long? = null
    var lastAttachFailed: Boolean = false
        private set

    suspend fun update(snapshot: PlaybackSnapshot?) {
        if (snapshot != null) pending = snapshot
        pending?.let { next ->
            val file = withContext(io) {
                File.createTempFile("translated-", ".${next.extension}", directory).also {
                    try { it.writeText(next.content, Charsets.UTF_8) }
                    catch (failure: Throwable) { it.delete(); throw failure }
                }
            }
            owned += file
            latest = file
            pending = null
        }
        val target = latest
        if (target != null && player.ready && player.selectedPath != target.absolutePath) {
            lastAttachFailed = !player.select(target.absolutePath)
            if (!lastAttachFailed) attachedGeneration = player.mediaGeneration
        } else lastAttachFailed = false
        cleanUnused()
    }

    suspend fun close() {
        if (owned.any { it.absolutePath == player.selectedPath } &&
            attachedGeneration == player.mediaGeneration) {
            player.select(source)
        }
        latest = null
        // A failed restore must not leave mpv pointing at a deleted file.
        cleanUnused()
    }

    private suspend fun cleanUnused() {
        val unused = owned.filter { it != latest && it.absolutePath != player.selectedPath }
        withContext(io) { unused.forEach { it.delete() } }
        owned.removeAll(unused.toSet())
    }
}
