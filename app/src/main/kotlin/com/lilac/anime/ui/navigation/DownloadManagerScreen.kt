package com.lilac.anime.ui.navigation

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lilac.anime.data.offline.DownloadQueuePolicy
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.offline.OfflineDownloadManager
import com.lilac.anime.viewmodel.AnimeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(vm: AnimeViewModel, back: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val statuses by vm.downloadStatuses.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var deleting by remember { mutableStateOf<MpvOfflineStore.Status?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun perform(action: () -> Unit) { runCatching(action).onFailure { message = it.message ?: "요청을 처리하지 못했습니다." } }
    val visible = statuses.filter { when (filter) {
        "active" -> it.state in setOf("queued", "downloading", "paused")
        "failed" -> it.state == "failed"
        "completed" -> it.state == "completed"
        else -> true
    } }.sortedWith(compareBy<MpvOfflineStore.Status> { it.animeId }.thenBy { it.episodeNumber }.thenBy { it.episodeKey })
    Scaffold(topBar = { TopAppBar(title = { Text("다운로드 관리") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "뒤로") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = statuses.any { DownloadQueuePolicy.canPause(it.state) }, onClick = { perform { OfflineDownloadManager.cancelAll(context) } }) { Text("전체 일시정지") }
                OutlinedButton(enabled = statuses.any { it.state == "paused" && !it.sourceUrl.isNullOrBlank() }, onClick = { perform { OfflineDownloadManager.resumeAll(context) } }) { Text("이어받기") }
                OutlinedButton(enabled = statuses.any { it.state == "failed" && !it.sourceUrl.isNullOrBlank() }, onClick = { perform { OfflineDownloadManager.resumeAll(context, failuresOnly = true) } }) { Text("실패 재시도") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "전체", "active" to "진행 / 일시정지", "failed" to "실패", "completed" to "완료").forEach { (key, title) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(title) })
                }
            }
            message?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("표시할 다운로드가 없습니다.") }
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { status ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(status.title.ifBlank { "${status.animeId} · ${status.episodeKey}화" }, fontWeight = FontWeight.Bold)
                            Text("${DownloadQueuePolicy.label(status.state)} · ${(status.progress.coerceIn(0f, 1f) * 100).toInt()}%")
                            LinearProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 3) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (DownloadQueuePolicy.canPause(status.state)) TextButton(onClick = { perform { OfflineDownloadManager.pause(context, status.animeId, status.episodeId) } }) { Text("일시정지") }
                                if (DownloadQueuePolicy.canResume(status.state, status.sourceUrl)) TextButton(onClick = { perform { OfflineDownloadManager.resume(context, status.animeId, status.episodeId) } }) { Text(if (status.state == "failed") "재시도" else "이어받기") }
                                if (status.state == "completed") TextButton(onClick = { navigate("player/${Uri.encode(status.animeId)}/${Uri.encode(status.episodeId)}") }) { Text("재생") }
                                TextButton(onClick = { deleting = status }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { status ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("다운로드 삭제") },
            text = { Text("${status.title}\n저장된 영상과 자막, 이어받기 파일을 삭제합니다.") },
            confirmButton = { TextButton(onClick = { perform { OfflineDownloadManager.remove(context, status.animeId, status.episodeId) }; deleting = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } })
    }
}
