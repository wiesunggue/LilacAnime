package com.lilac.anime.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lilac.anime.data.subtitle.SubtitleDiscoveryService.Choice

/** One active subtitle file. Translation is a display mode of that same file. */
@Composable
fun SubtitleSelectionDialog(
    title: String,
    choices: List<Choice>,
    selectedKey: String?,
    searching: Boolean,
    applying: Boolean,
    translate: Boolean,
    error: String?,
    onSelect: (Choice) -> Unit,
    onTranslate: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("재생할 자막 파일 1개 선택") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(title)
                Text("Anissia: 한국어 자막 · Jimaku: 주로 일본어 자막\nRe:Anime / Linkkf: 영상 사이트 제공 자막 · 저장됨: 기기 파일")
                Spacer(Modifier.height(8.dp))
                if (searching) Text("자막 검색 중… 언제든 취소할 수 있습니다.")
                if (applying) Text("선택한 자막을 불러오는 중…")
                if (!searching && choices.isEmpty()) Text("사용 가능한 자막이 없습니다.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    items(choices, key = { it.key }) { choice ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = choice.key == selectedKey,
                                enabled = !applying,
                                role = Role.RadioButton,
                                onClick = { onSelect(choice) },
                            ).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice.key == selectedKey, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text("${choice.label} · ${choice.language}")
                                Text(choice.title, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = translate, onCheckedChange = if (applying) null else onTranslate)
                    Text("선택한 원문을 AI로 한국어 번역")
                }
                Text("한국어 파일은 원본 그대로 재생합니다. 다른 자막을 추가 선택할 필요가 없습니다.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onPlay, enabled = !searching && !applying && choices.any { it.key == selectedKey }) {
                Text("이 자막으로 재생")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (searching || applying) "취소 · 자막 없이 재생" else "자막 없이 재생") }
        },
    )
}
