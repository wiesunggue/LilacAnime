from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: expected source block not found in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


PLAYER = "app/src/main/kotlin/com/lilac/anime/player/PlayerScreen.kt"
REALTIME = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslator.kt"
DOWNLOADER = "app/src/main/kotlin/com/lilac/anime/data/subtitle/SubtitleDownloader.kt"
FORMAT = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/SubtitleFormatTranslator.kt"
AI_SETTINGS = "app/src/main/kotlin/com/lilac/anime/ui/settings/AiSettingsScreen.kt"

# A cancelled cloud request uses blocking OkHttp execute(). If it is still in
# flight while a new episode starts, a single process-wide mutex can stall the
# new subtitle session for the old request's full timeout. Give each prepared
# session its own mutex; generation checks continue to discard stale results.
replace_once(
    REALTIME,
    "    private val translationLock = Mutex()\n",
    "    private var translationLock = Mutex()\n",
    "session-scoped translation mutex",
)
replace_once(
    REALTIME,
    """            worker?.cancel()
            worker = null
            cache.clear()
""",
    """            worker?.cancel()
            worker = null
            translationLock = Mutex()
            cache.clear()
""",
    "reset translation mutex on prepare",
)
# clear() has the same worker/caches block a second time.
p = Path(REALTIME)
s = p.read_text(encoding="utf-8")
needle = """            worker?.cancel()
            worker = null
            cache.clear()
            cues = emptyList()
"""
if needle not in s:
    raise SystemExit("reset translation mutex on clear: expected source block not found")
s = s.replace(
    needle,
    """            worker?.cancel()
            worker = null
            translationLock = Mutex()
            cache.clear()
            cues = emptyList()
""",
    1,
)
p.write_text(s, encoding="utf-8")

# Initial subtitle selection used to expose the source track before changing the
# mode to Korean translation, producing a short flash of the source subtitle.
replace_once(
    PLAYER,
    """            engine.replaceSubtitleTrack(activePath)
            engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
            setSubtitleVisibleForMode(subtitleEnabled)
""",
    """            engine.replaceSubtitleTrack(activePath)
            engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
            setSubtitleVisibleForMode(subtitleEnabled && initialTranslationMode != "korean")
""",
    "initial subtitle source flash",
)
p = Path(PLAYER)
s = p.read_text(encoding="utf-8")
old = """                    subtitleSelectionNeedsUserChoice = false
                    setSubtitleVisibleForMode(subtitleEnabled)
                    subtitleTranslationMode = if (initialTranslationMode == "korean" && matched.any { it.language != "한국어" }) "korean" else "original"
                    engine.play()
"""
new = """                    subtitleSelectionNeedsUserChoice = false
                    subtitleTranslationMode = if (initialTranslationMode == "korean" && matched.any { it.language != "한국어" }) "korean" else "original"
                    setSubtitleVisibleForMode(subtitleEnabled)
                    engine.play()
"""
if old not in s:
    raise SystemExit("auto subtitle translation visibility ordering block not found")
s = s.replace(old, new, 1)
old2 = """                                subtitleSelectionNeedsUserChoice = false
                                setSubtitleVisibleForMode(subtitleEnabled)
                                subtitleTranslationMode = if (initialTranslationMode == "korean" && matched.any { it.language != "한국어" }) "korean" else "original"
                                engine.play()
"""
new2 = """                                subtitleSelectionNeedsUserChoice = false
                                subtitleTranslationMode = if (initialTranslationMode == "korean" && matched.any { it.language != "한국어" }) "korean" else "original"
                                setSubtitleVisibleForMode(subtitleEnabled)
                                engine.play()
"""
if old2 not in s:
    raise SystemExit("late subtitle discovery visibility ordering block not found")
s = s.replace(old2, new2, 1)
p.write_text(s, encoding="utf-8")

# Decode common BOM encodings before format sniffing. Otherwise a UTF-16 ASS/SRT
# response can look like garbage and be incorrectly cached as .vtt.
replace_once(
    DOWNLOADER,
    '''                val text = bytes.toString(Charsets.UTF_8).removePrefix("\\uFEFF")
''',
    '''                val text = when {
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE)
                    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE)
                    else -> String(bytes, Charsets.UTF_8)
                }.removePrefix("\\uFEFF")
''',
    "remote subtitle BOM decoding",
)

# The stability generator writes Kotlin code from a Python triple-quoted string.
# Fix the escaping in the generated ASS parser so Kotlin sees literal regex
# backslashes instead of unsupported \p / \{ / \N escape sequences.
p = Path(FORMAT)
s = p.read_text(encoding="utf-8")
fixes = [
    (r'Regex("\p{L}")', r'Regex("\\p{L}")'),
    (r'Regex("(?s)\{[^}]*\}")', r'Regex("(?s)\\{[^}]*\\}")'),
    (r'.replace("\N", " " )', r'.replace("\\N", " " )'),
]
for old_text, new_text in fixes:
    if old_text not in s:
        raise SystemExit(f"generated Kotlin escape hotfix not found: {old_text}")
    s = s.replace(old_text, new_text)
p.write_text(s, encoding="utf-8")

# Desktop parity makes the provider chip row horizontally scrollable. The
# original settings file imported verticalScroll but not horizontalScroll.
p = Path(AI_SETTINGS)
s = p.read_text(encoding="utf-8")
if "import androidx.compose.foundation.horizontalScroll\n" not in s:
    marker = "import androidx.compose.foundation.verticalScroll\n"
    if marker not in s:
        raise SystemExit("AiSettingsScreen scroll import insertion point not found")
    s = s.replace(marker, marker + "import androidx.compose.foundation.horizontalScroll\n", 1)
p.write_text(s, encoding="utf-8")

print("Applied LilacAnime player/subtitle follow-up fixes successfully")
