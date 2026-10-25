package com.lilac.anime.ui.search

import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.Anime
import com.lilac.anime.data.CollectionQuery
import com.lilac.anime.data.PlaybackHistory
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.ui.navigation.AppScaffold
import com.lilac.anime.viewmodel.AnimeViewModel

@Composable
internal fun CollectionMenu(value: String, choices: Map<String, String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(choices[value] ?: choices.values.first()); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { change(key); expanded = false }) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AnimeViewModel, open: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lilac_collection_ui", 0) }
    val isOffline by vm.isOffline.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()
    var downloaded by remember { mutableStateOf<List<Anime>>(emptyList()) }
    LaunchedEffect(Unit) { vm.refreshDownloads() }
    LaunchedEffect(downloadedIds, vm.homeAnime, vm.allAnime) { downloaded = vm.getDownloadedAnimeList(context) }
    var tab by rememberSaveable { mutableIntStateOf(if (isOffline) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(prefs.getString("library_source", "all") ?: "all") }
    var sort by rememberSaveable { mutableStateOf(prefs.getString("library_sort", "saved") ?: "saved") }
    var state by rememberSaveable { mutableStateOf("all") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }
    val saved = vm.library.mapNotNull { vm.getAnime(context, it) }
    val visible = CollectionQuery.apply(if (tab == 0) saved else downloaded, vm.watchHistory, query, source, state, sort)
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    LaunchedEffect(vm.library, tab) { selected = selected.intersect(if (tab == 0) vm.library else emptySet()) }

    AppScaffold(selected = "library", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected.isEmpty()) "보관함" else "${selected.size}개 선택", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { val ids = visible.map { it.id }.toSet(); selected = if (selected.containsAll(ids)) selected - ids else selected + ids }) { Icon(Icons.Default.SelectAll, "검색 결과 전체선택 / 해제") }
                    IconButton(onClick = { confirming = true }) { Icon(Icons.Default.Delete, "내 목록에서 삭제") }
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "선택 취소") }
                } else TextButton(onClick = { onNavigate("downloads") }) { Text("다운로드 관리") }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; selected = emptySet() }, text = { Text("내 목록 (${saved.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1; selected = emptySet() }, text = { Text("다운로드 (${downloaded.size})") })
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("제목 · 원제 · 장르 검색") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "검색 지우기") } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CollectionMenu(source, CollectionQuery.sources) { source = it; prefs.edit().putString("library_source", it).apply() }
                CollectionMenu(sort, CollectionQuery.sorts) { sort = it; prefs.edit().putString("library_sort", it).apply() }
                CollectionMenu(state, CollectionQuery.states) { state = it }
            }
            Text("${visible.size}개 · 길게 눌러 내 목록 선택", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp)
            if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("조건에 맞는 작품이 없습니다.") }
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { anime ->
                    val progress = vm.getLatestProgress(anime.id)
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selected.isNotEmpty()) toggle(anime.id) else open(anime) }, onLongClick = { if (tab == 0) toggle(anime.id) }),
                        colors = CardDefaults.cardColors(containerColor = if (anime.id in selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selected.isNotEmpty()) Checkbox(checked = anime.id in selected, onCheckedChange = { toggle(anime.id) })
                            AnimeImage(model = anime.poster, contentDescription = anime.title, modifier = Modifier.size(72.dp, 104.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(anime.title, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(listOf(CollectionQuery.sources[PlaybackHistory.sourceFor(anime, "")] ?: "", anime.year).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp)
                                Text(anime.genres.joinToString(" · "), fontSize = 11.sp, maxLines = 1)
                                if (progress != null) {
                                    Text("${PlaybackHistory.episodeLabel(progress.episodeKey, progress.episodeNumber)}화 · ${(PlaybackHistory.fraction(progress.progress) * 100).toInt()}%", fontSize = 12.sp)
                                    LinearProgressIndicator(progress = { PlaybackHistory.fraction(progress.progress) }, modifier = Modifier.fillMaxWidth())
                                }
                                if (selected.isEmpty()) TextButton(onClick = { onNavigate("resume/${Uri.encode(anime.id)}") }, contentPadding = PaddingValues(0.dp)) { Text(if (progress == null) "첫 화 재생" else "이어보기") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("내 목록에서 ${selected.size}개 삭제") },
        text = { Text("선택한 작품을 내 목록에서 삭제합니다. 시청 기록과 다운로드는 유지됩니다. 연결된 기기에도 반영됩니다.") },
        confirmButton = { TextButton(onClick = { vm.removeFromLibrary(context, selected); selected = emptySet(); confirming = false }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("취소") } })
}
