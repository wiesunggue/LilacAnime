from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: expected source block not found in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


MANAGER = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/TranslationManager.kt"
REALTIME = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslator.kt"
FORMAT = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/SubtitleFormatTranslator.kt"
CATALOG = "app/src/main/kotlin/com/lilac/anime/data/subtitle/translation/providers/CloudModelCatalog.kt"
SETTINGS = "app/src/main/kotlin/com/lilac/anime/ui/settings/AiSettingsScreen.kt"

# ---------------------------------------------------------------------------
# Claude / Anthropic provider wiring (desktop parity)
# ---------------------------------------------------------------------------
replace_once(
    MANAGER,
    "import com.lilac.anime.data.subtitle.translation.providers.QwenTranslator\n",
    "import com.lilac.anime.data.subtitle.translation.providers.QwenTranslator\nimport com.lilac.anime.data.subtitle.translation.providers.AnthropicTranslator\n",
    "manager Claude import",
)
replace_once(
    MANAGER,
    '        "qwen" -> QwenTranslator(context)\n        else -> LocalTranslator(context)\n',
    '        "qwen" -> QwenTranslator(context)\n        "claude" -> AnthropicTranslator(context)\n        else -> LocalTranslator(context)\n',
    "manager Claude provider",
)
replace_once(
    MANAGER,
    '            "qwen" -> prefs.getString("pref_qwen_model", "qwen-plus")\n            else -> null\n',
    '            "qwen" -> prefs.getString("pref_qwen_model", "qwen-plus")\n            "claude" -> prefs.getString("pref_claude_model", "")\n            else -> null\n',
    "manager Claude cache signature",
)
replace_once(
    MANAGER,
    '            "qwen" -> (createProvider(context, providerId) as QwenTranslator).testConnection()\n            else -> error("${createProvider(context, providerId).displayName}는 API 키 연결 테스트를 지원하지 않습니다.")\n',
    '            "qwen" -> (createProvider(context, providerId) as QwenTranslator).testConnection()\n            "claude" -> (createProvider(context, providerId) as AnthropicTranslator).testConnection()\n            else -> error("${createProvider(context, providerId).displayName}는 API 키 연결 테스트를 지원하지 않습니다.")\n',
    "manager Claude connection test",
)

replace_once(
    REALTIME,
    '        val providerIds = (listOf(primaryId) + listOf("gemini", "openai", "deepl", "qwen").filter { it != primaryId }).distinct()\n',
    '        val providerIds = (listOf(primaryId) + listOf("gemini", "openai", "deepl", "qwen", "claude").filter { it != primaryId }).distinct()\n',
    "realtime Claude cloud fallback",
)

# Desktop's optional "remove SFX / speaker labels before translation" feature.
replace_once(
    REALTIME,
    '''        return lineBreaksNormalized
            .replace(Regex("(?s)\\\\{[^}]*\\\\}"), "")
            .replace(Regex("(?is)<[^>]+>"), "")
            .trim()
''',
    '''        val clean = lineBreaksNormalized
            .replace(Regex("(?s)\\\\{[^}]*\\\\}"), "")
            .replace(Regex("(?is)<[^>]+>"), "")
            .trim()
        val dropCaptions = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .getBoolean("pref_translation_drop_captions", false)
        return TranslationTextCleaner.clean(clean, dropCaptions)
''',
    "realtime desktop caption cleanup",
)

# Full-file translation gets the same cleanup option and Claude-sized batches.
replace_once(
    FORMAT,
    '        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)\n        val contextCueCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 6)\n',
    '        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)\n        val contextCueCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 6)\n        val dropCaptions = prefs.getBoolean("pref_translation_drop_captions", false)\n',
    "file translator caption preference",
)

p = Path(FORMAT)
s = p.read_text(encoding="utf-8")
for old, new in [
    ("modelText(cue.text, cue.kind)", "modelText(cue.text, cue.kind, dropCaptions)"),
    ("modelText(previous.text, previous.kind)", "modelText(previous.text, previous.kind, dropCaptions)"),
    ("modelText(it.text, it.kind)", "modelText(it.text, it.kind, dropCaptions)"),
]:
    s = s.replace(old, new)
old_fun = '''    private fun modelText(text: String, kind: String): String {
        var value = text.replace("\\r\\n", "\\n").replace('\\r', '\\n')
        if (kind == "srt") value = value.replace("\\n", "\\\\N")
        value = value.replace(Regex("(?s)\\\\{[^}]*\\\\}"), "")
        value = value.replace(Regex("(?is)<[^>]+>"), "")
        return value.trim()
    }
'''
new_fun = '''    private fun modelText(text: String, kind: String, dropCaptions: Boolean = false): String {
        var value = text.replace("\\r\\n", "\\n").replace('\\r', '\\n')
        if (kind == "srt") value = value.replace("\\n", "\\\\N")
        value = value.replace(Regex("(?s)\\\\{[^}]*\\\\}"), "")
        value = value.replace(Regex("(?is)<[^>]+>"), "")
        return TranslationTextCleaner.clean(value.trim(), dropCaptions)
    }
'''
if old_fun not in s:
    raise SystemExit("file translator modelText block not found")
s = s.replace(old_fun, new_fun, 1)
s = s.replace('''                    "qwen" -> 40
                    "deepl" -> 50
''', '''                    "qwen" -> 40
                    "claude" -> 100
                    "deepl" -> 50
''', 1)
s = s.replace('''                    "qwen" -> 7000
                    "deepl" -> 20000
''', '''                    "qwen" -> 7000
                    "claude" -> 12000
                    "deepl" -> 20000
''', 1)
p.write_text(s, encoding="utf-8")

# Cloud model discovery: Claude's /v1/models endpoint uses x-api-key rather than Bearer.
replace_once(
    CATALOG,
    '''            "qwen" -> (if (qwenRegion == "china") "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com") + "/compatible-mode/v1/models"
            else -> error("모델 목록을 지원하지 않는 제공자입니다: $provider")
''',
    '''            "qwen" -> (if (qwenRegion == "china") "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com") + "/compatible-mode/v1/models"
            "claude" -> "https://api.anthropic.com/v1/models?limit=100"
            else -> error("모델 목록을 지원하지 않는 제공자입니다: $provider")
''',
    "catalog Claude endpoint",
)
replace_once(
    CATALOG,
    '''        val builder = Request.Builder().url(url).get()
        if (provider != "gemini") builder.header("Authorization", if (provider == "qwen") "Bearer $key" else "Bearer $key")
''',
    '''        val builder = Request.Builder().url(url).get()
        when (provider) {
            "gemini" -> Unit
            "claude" -> builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            else -> builder.header("Authorization", "Bearer $key")
        }
''',
    "catalog Claude authentication",
)
replace_once(
    CATALOG,
    '''                        "qwen" -> if (id.contains("qwen", true) && !Regex("audio|vl|coder", RegexOption.IGNORE_CASE).containsMatchIn(id)) add(id)
                    }
''',
    '''                        "qwen" -> if (id.contains("qwen", true) && !Regex("audio|vl|coder", RegexOption.IGNORE_CASE).containsMatchIn(id)) add(id)
                        "claude" -> if (id.startsWith("claude-", true)) add(id)
                    }
''',
    "catalog Claude model filtering",
)

# ---------------------------------------------------------------------------
# AI settings UI: Claude key/model selector + desktop caption-cleanup switch.
# ---------------------------------------------------------------------------
replace_once(
    SETTINGS,
    '    val keyProviders = listOf("gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen")\n',
    '    val keyProviders = listOf("gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen", "claude" to "Claude")\n',
    "settings Claude key provider",
)
replace_once(
    SETTINGS,
    '''    var qwenModel by remember { mutableStateOf(aiPrefs.getString("pref_qwen_model", "qwen-plus") ?: "qwen-plus") }
    var qwenRegion by remember { mutableStateOf(aiPrefs.getString("pref_qwen_region", "international") ?: "international") }
    var geminiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var openAiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var qwenModels by remember { mutableStateOf<List<String>>(emptyList()) }
''',
    '''    var qwenModel by remember { mutableStateOf(aiPrefs.getString("pref_qwen_model", "qwen-plus") ?: "qwen-plus") }
    var claudeModel by remember { mutableStateOf(aiPrefs.getString("pref_claude_model", "") ?: "") }
    var qwenRegion by remember { mutableStateOf(aiPrefs.getString("pref_qwen_region", "international") ?: "international") }
    var dropCaptions by remember { mutableStateOf(aiPrefs.getBoolean("pref_translation_drop_captions", false)) }
    var geminiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var openAiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var qwenModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var claudeModels by remember { mutableStateOf<List<String>>(emptyList()) }
''',
    "settings Claude/caption state",
)
replace_once(
    SETTINGS,
    '''                        "qwen" -> { qwenModels = fetched; if (qwenModel !in fetched && fetched.isNotEmpty()) { qwenModel = fetched.first(); aiPrefs.edit().putString("pref_qwen_model", qwenModel).apply() } }
                    }
''',
    '''                        "qwen" -> { qwenModels = fetched; if (qwenModel !in fetched && fetched.isNotEmpty()) { qwenModel = fetched.first(); aiPrefs.edit().putString("pref_qwen_model", qwenModel).apply() } }
                        "claude" -> { claudeModels = fetched; if (claudeModel !in fetched && fetched.isNotEmpty()) { claudeModel = fetched.first(); aiPrefs.edit().putString("pref_claude_model", claudeModel).apply() } }
                    }
''',
    "settings Claude model loading",
)
replace_once(
    SETTINGS,
    '''            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("local" to "로컬 AI", "gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen").forEach { (id,label) ->
                    FilterChip(selected = settings.translationProvider == id, onClick = { vm.updatePlayerSettings(context, settings.copy(translationProvider=id)) }, label={Text(label)})
                }
            }
''',
    '''            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("local" to "로컬 AI", "gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen", "claude" to "Claude").forEach { (id,label) ->
                    FilterChip(selected = settings.translationProvider == id, onClick = { vm.updatePlayerSettings(context, settings.copy(translationProvider=id)) }, label={Text(label)})
                }
            }
''',
    "settings translation engine row",
)
replace_once(
    SETTINGS,
    '''            if (settings.translationProvider != "local") {
                Text("클라우드 API는 네트워크와 API 사용량이 필요합니다. 재생 중 번역은 미리 자막을 읽어 한 cue씩 요청하므로 로컬 모델보다 지연될 수 있습니다.", fontSize=11.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
            }

            Spacer(Modifier.height(20.dp))
''',
    '''            if (settings.translationProvider != "local") {
                Text("클라우드 API는 네트워크와 API 사용량이 필요합니다. 재생 중 번역은 미리 자막을 읽어 한 cue씩 요청하므로 로컬 모델보다 지연될 수 있습니다.", fontSize=11.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("효과음 · 화자 이름 빼고 번역", fontSize=13.sp, fontWeight=FontWeight.Medium)
                    Text("Desktop처럼 (한숨), [SFX], SOTA: 같은 짧은 라벨을 AI 입력에서 제외합니다. 원본 자막 표시와 시간 정보는 바뀌지 않습니다.", fontSize=10.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                }
                Switch(checked=dropCaptions,onCheckedChange={dropCaptions=it;aiPrefs.edit().putBoolean("pref_translation_drop_captions",it).apply()})
            }

            Spacer(Modifier.height(20.dp))
''',
    "settings caption cleanup switch",
)
replace_once(
    SETTINGS,
    '''            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick={if(qwenModels.isEmpty())loadCloudModels("qwen") else expandedCloudModel=if(expandedCloudModel=="qwen")null else "qwen"},modifier=Modifier.fillMaxWidth(),enabled=modelListLoading==null){Text("Qwen: $qwenModel",maxLines=2)}
                    DropdownMenu(expanded=expandedCloudModel=="qwen",onDismissRequest={expandedCloudModel=null},modifier=Modifier.heightIn(max=360.dp)){qwenModels.forEach{item->DropdownMenuItem(text={Text(item)},onClick={qwenModel=item;aiPrefs.edit().putString("pref_qwen_model",item).apply();expandedCloudModel=null;modelListMessage="Qwen 모델 선택: $item"})}}
                }
                OutlinedButton(onClick={loadCloudModels("qwen")},enabled=modelListLoading==null){Text(if(modelListLoading=="qwen")"조회 중" else "목록 조회")}
            }
            modelListMessage?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.7f))}
''',
    '''            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick={if(qwenModels.isEmpty())loadCloudModels("qwen") else expandedCloudModel=if(expandedCloudModel=="qwen")null else "qwen"},modifier=Modifier.fillMaxWidth(),enabled=modelListLoading==null){Text("Qwen: $qwenModel",maxLines=2)}
                    DropdownMenu(expanded=expandedCloudModel=="qwen",onDismissRequest={expandedCloudModel=null},modifier=Modifier.heightIn(max=360.dp)){qwenModels.forEach{item->DropdownMenuItem(text={Text(item)},onClick={qwenModel=item;aiPrefs.edit().putString("pref_qwen_model",item).apply();expandedCloudModel=null;modelListMessage="Qwen 모델 선택: $item"})}}
                }
                OutlinedButton(onClick={loadCloudModels("qwen")},enabled=modelListLoading==null){Text(if(modelListLoading=="qwen")"조회 중" else "목록 조회")}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick={if(claudeModels.isEmpty())loadCloudModels("claude") else expandedCloudModel=if(expandedCloudModel=="claude")null else "claude"},modifier=Modifier.fillMaxWidth(),enabled=modelListLoading==null){Text("Claude: ${claudeModel.ifBlank { "자동" }}",maxLines=2)}
                    DropdownMenu(expanded=expandedCloudModel=="claude",onDismissRequest={expandedCloudModel=null},modifier=Modifier.heightIn(max=360.dp)){claudeModels.forEach{item->DropdownMenuItem(text={Text(item)},onClick={claudeModel=item;aiPrefs.edit().putString("pref_claude_model",item).apply();expandedCloudModel=null;modelListMessage="Claude 모델 선택: $item"})}}
                }
                OutlinedButton(onClick={loadCloudModels("claude")},enabled=modelListLoading==null){Text(if(modelListLoading=="claude")"조회 중" else "목록 조회")}
            }
            modelListMessage?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.7f))}
''',
    "settings Claude model selector",
)

print("Applied LilacAnime desktop parity translation features successfully")
