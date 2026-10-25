package com.lilac.anime.data.subtitle.translation

import org.jsoup.parser.Parser

/** Plain dialogue for models/renderers. Decode entities BEFORE removing markup. */
internal object SubtitleMarkup {
    private val breaks = Regex("(?i)<br\\s*/?\\s*>")
    private val tags = Regex("(?is)</?(?:font|span|b|i|u|s|strike|ruby|rt|rp|p|div|c(?:\\.[\\w-]+)*|v|lang)(?=[\\s>/])[^>]*>")
    private val timestamps = Regex("<\\d{2}:\\d{2}(?::\\d{2})?\\.\\d{3}>")
    private val markers = Regex("(?i)</?LILAC_\\d+>")
    private val overrides = Regex("\\{\\\\[^{}]*}")

    fun stripHtml(text: String): String {
        var value = text.replace("\r\n", "\n").replace('\r', '\n')
        // Some providers double escape entities. Bound the passes for malformed input.
        repeat(4) {
            val decoded = Parser.unescapeEntities(value, false)
            if (decoded == value) return@repeat
            value = decoded
        }
        return value.replace(breaks, "\n").replace(tags, "")
            .replace(timestamps, "").replace(markers, "").replace('\u00a0', ' ').trim()
    }

    fun plainText(text: String): String = stripHtml(text).replace(overrides, "").trim()
}
