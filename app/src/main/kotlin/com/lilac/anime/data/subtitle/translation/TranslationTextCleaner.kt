package com.lilac.anime.data.subtitle.translation

/**
 * Optional subtitle-input cleanup ported from the desktop translator.
 *
 * It removes short speaker/caption labels before text is sent to an AI model,
 * while leaving the original subtitle event untouched for rendering/timing.
 */
object TranslationTextCleaner {
    private val leadingBracketLabel = Regex("^[（(［\\[]([^（）()［］\\[\\]\\n]{1,16})[）)］\\]]\\s*(?=\\S)")
    private val bracketLabel = Regex("[（(［\\[]([^（）()［］\\[\\]\\n]{1,24})[）)］\\]]")
    private val japaneseSpeaker = Regex("^[\\u3400-\\u9fff\\u30a0-\\u30ffー・々]{1,12}[：:]\\s*")
    private val latinSpeaker = Regex("^[A-Z][A-Z0-9 .'-]{0,24}:\\s+")
    private val sentenceMarks = Regex("[。、！？!?…]")
    private val soundSuffix = Regex("(?:音|声|息|笑い|笑|泣き|悲鳴|咳|咳払い|拍手|歓声|鼻歌|舌打ち|いびき|あくび|くしゃみ|うめき|叫び|ノック|チャイム|ベル|着信|振動|音楽|BGM)$")
    private val japaneseSimpleLabel = Regex("^[\\u3400-\\u9fff\\u30a0-\\u30ffー・々\\s]+$")

    fun clean(text: String, enabled: Boolean): String {
        if (!enabled || text.isBlank()) return text
        return text.split("\\N").joinToString("\\N") { rawLine ->
            var line = rawLine.trim()
            line = leadingBracketLabel.replace(line) { match ->
                val inside = match.groupValues[1]
                if (sentenceMarks.containsMatchIn(inside)) match.value else ""
            }
            line = bracketLabel.replace(line) { match ->
                val inside = match.groupValues[1].trim()
                if (isCaptionLabel(inside)) " " else match.value
            }
            line = japaneseSpeaker.replace(line, "")
            line = latinSpeaker.replace(line, "")
            line.replace(Regex("\\s{2,}"), " ").trim()
        }.trim()
    }

    private fun isCaptionLabel(value: String): Boolean {
        if (value.isBlank() || sentenceMarks.containsMatchIn(value)) return false
        if (japaneseSimpleLabel.matches(value) || soundSuffix.containsMatchIn(value)) return true
        val containsJapanese = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]").containsMatchIn(value)
        return !containsJapanese && value.split(Regex("\\s+")).size <= 3
    }
}
