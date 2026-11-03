package com.lilac.anime.data.subtitle.translation

import org.jsoup.parser.Parser

/** Plain dialogue for models/renderers. Decode entities BEFORE removing markup. */
internal object SubtitleMarkup {
    private val breaks = Regex("(?i)<br\\s*/?\\s*>")
    private val tags = Regex("(?is)</?(?:font|span|b|i|u|s|strike|ruby|rt|rp|p|div|c(?:\\.[\\w-]+)*|v|lang)(?=[\\s>/])[^>]*>")
    private val timestamps = Regex("<\\d{2}:\\d{2}(?::\\d{2})?\\.\\d{3}>")
    private val markers = Regex("(?i)</?LILAC_\\d+>")
    // Android uses ICU: a literal closing brace must be escaped as well.
    private val overrides = Regex("\\{\\\\[^{}]*\\}")

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

    fun isAssDrawing(text: String): Boolean = Regex("\\{[^{}]*\\}").findAll(text)
        .any { Regex("\\\\p\\s*[1-9]\\d*").containsMatchIn(it.value) }

    fun restoreAssMarkup(original: String, translated: String): String {
        val tokens = Regex("\\{[^{}]*\\}")
        val source = original.replace("\\N", "\n")
        val text = plainText(translated).replace("\\N", "\n")
        val visible = plainText(source.replace(tokens, ""))
        val sourceLength = visible.codePointCount(0, visible.length).coerceAtLeast(1)
        val targetLength = text.codePointCount(0, text.length)
        val insertions = linkedMapOf<Int, MutableList<String>>()
        for (token in tokens.findAll(source)) {
            val before = plainText(source.substring(0, token.range.first).replace(tokens, ""))
            val point = (before.codePointCount(0, before.length).toDouble() / sourceLength * targetLength)
                .toInt().coerceIn(0, targetLength)
            val offset = text.offsetByCodePoints(0, point)
            insertions.getOrPut(offset) { mutableListOf() }.add(token.value)
        }
        // Insert around real newlines/codepoints, then encode ASS line breaks.
        // A tag can never split a surrogate pair or the two characters in \\N.
        return buildString {
            var cursor = 0
            for ((offset, markup) in insertions.toSortedMap()) {
                append(text, cursor, offset)
                markup.forEach { append(it) }
                cursor = offset
            }
            append(text, cursor, text.length)
        }.replace("\n", "\\N")
    }

    /** Restore source styling around model text at the native SRT/VTT/SAMI
     * boundary. Models receive plain text; source markup never has to be guessed. */
    fun restoreNativeMarkup(original: String, translated: String): String {
        val text = plainText(translated).replace("\\N", "\n")
        val source = original.replace("\\N", "\n")
        val length = plainText(source).codePointCount(0, plainText(source).length).coerceAtLeast(1)
        val targetLength = text.codePointCount(0, text.length)
        val tokens = Regex("(?s)<[^>]+>|\\{\\\\[^{}]*\\}").findAll(source)
        val insertions = linkedMapOf<Int, MutableList<String>>()
        for (token in tokens) {
            // The provider's line breaks already represent source <br> tags.
            if (breaks.matches(token.value) && '\n' in text) continue
            val before = plainText(source.substring(0, token.range.first))
            val point = (before.codePointCount(0, before.length).toDouble() / length * targetLength)
                .toInt().coerceIn(0, targetLength)
            val offset = text.offsetByCodePoints(0, point)
            insertions.getOrPut(offset) { mutableListOf() }.add(token.value)
        }
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            var cursor = 0
            for ((offset, markup) in insertions.toSortedMap()) {
                append(escape(text.substring(cursor, offset)))
                markup.forEach { append(it) }
                cursor = offset
            }
            append(escape(text.substring(cursor)))
        }
    }
}
