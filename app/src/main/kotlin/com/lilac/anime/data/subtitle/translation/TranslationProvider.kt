package com.lilac.anime.data.subtitle.translation

/** A usable response arrived, but it cannot be mapped to the requested cues. */
internal class TranslationResponseException(message: String) : IllegalStateException(message)

interface TranslationProvider {
    val id: String
    val displayName: String
    suspend fun translateBatch(lines: List<String>): List<String>
}

interface TranslationSessionProvider : TranslationProvider {
    suspend fun beginSession()
    suspend fun endSession()
}
