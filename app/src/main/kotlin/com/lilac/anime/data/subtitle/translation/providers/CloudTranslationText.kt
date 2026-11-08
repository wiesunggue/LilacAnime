package com.lilac.anime.data.subtitle.translation.providers

import org.json.JSONArray
import org.json.JSONObject
import com.lilac.anime.data.subtitle.translation.TranslationResponseException

internal object CloudTranslationText {
    fun markedInput(lines: List<String>): String = lines.mapIndexed { index, line -> "<LILAC_${index + 1}> $line" }.joinToString("\n")

    fun parseMarked(text: String, original: List<String>): List<String> {
        if (original.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()

        // The desktop port asks OpenAI/Qwen for structured JSON when the model
        // supports it. Accept that response here too, while retaining the marker
        // format as the universal fallback for Gemini/older models.
        parseJsonLines(normalized, original)?.let { return it }
        val unfenced = normalized.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (unfenced.startsWith("{") || Regex("^\\[\\s*[\\{\\\"\\]]").containsMatchIn(unfenced) || normalized.startsWith("```json")) {
            throw TranslationResponseException("API의 JSON 응답에 자막 번역이 누락되었거나 형식이 잘못되었습니다.")
        }
        val matches = Regex("<LILAC_(\\d+)>").findAll(normalized).toList()
        if (matches.isEmpty()) {
            if (original.size == 1 && normalized.isNotBlank()) return listOf(stripFences(normalized))
            throw TranslationResponseException("API 응답에서 자막 구분 표식을 찾지 못했습니다.")
        }
        val result = MutableList(original.size) { "" }
        val seen = mutableSetOf<Int>()
        matches.forEachIndexed { matchIndex, match ->
            val lineIndex = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEachIndexed
            if (lineIndex !in result.indices || !seen.add(lineIndex)) {
                throw TranslationResponseException("API 응답의 자막 번호가 중복되었거나 범위를 벗어났습니다.")
            }
            val start = match.range.last + 1
            val end = matches.getOrNull(matchIndex + 1)?.range?.first ?: normalized.length
            result[lineIndex] = stripFences(normalized.substring(start, end).trim().removePrefix(":" ).trim())
        }
        if (result.indices.any { result[it].isBlank() }) throw TranslationResponseException("API 응답에서 일부 자막 번역이 누락되었습니다.")
        return result
    }

    private fun parseJsonLines(text: String, original: List<String>): List<String>? = runCatching {
        val clean = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val values = when {
            clean.startsWith("{") -> {
                val obj = JSONObject(clean)
                obj.optJSONArray("lines") ?: return@runCatching null
            }
            clean.startsWith("[") -> JSONArray(clean)
            else -> return@runCatching null
        }
        val result = MutableList(original.size) { "" }
        if (values.length() != original.size) return@runCatching null
        val seen = mutableSetOf<Int>()
        for (i in 0 until values.length()) {
            val item = values.optJSONObject(i) ?: return@runCatching null
            val index = item.optInt("i", i + 1) - 1
            if (index !in result.indices || !seen.add(index) || item.opt("t") !is String) return@runCatching null
            val translated = item.getString("t").trim()
            if (index in result.indices && translated.isNotBlank()) result[index] = translated
        }
        if (result.all { it.isNotBlank() }) result else null
    }.getOrNull()

    fun stripFences(text: String): String = text
        .removePrefix("```text").removePrefix("```txt").removePrefix("```")
        .removeSuffix("```").trim()
}
