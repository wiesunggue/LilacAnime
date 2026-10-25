package com.lilac.anime.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.data.CollectionQuery
import com.lilac.anime.viewmodel.AnimeViewModel

@Composable
internal fun BoxScope.PlayerConvenienceOverlay(
    vm: AnimeViewModel,
    anime: Anime,
    currentEpisode: Episode,
    orderedEpisodes: List<Episode>,
    currentEpisodeIndex: Int,
    boosted: Boolean,
    visible: Boolean,
    pickerOpen: Boolean,
    helpOpen: Boolean,
    loading: Boolean,
    playable: (Episode) -> Boolean,
    openPicker: () -> Unit,
    closePicker: () -> Unit,
    openHelp: () -> Unit,
    closeHelp: () -> Unit,
    select: (Episode) -> Unit
) {
        if (boosted) {
            Surface(Modifier.align(Alignment.TopCenter).padding(top = 24.dp), color = Color.Black.copy(.7f), shape = RoundedCornerShape(12.dp)) {
                Text("▶▶ 2배속", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White)
            }
        }
        if (visible) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(onClick = { openPicker() }, color = Color.Black.copy(.65f), shape = RoundedCornerShape(12.dp)) { Text("회차 목록 · E", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, fontSize = 12.sp) }
                Surface(onClick = { openHelp() }, color = Color.Black.copy(.65f), shape = RoundedCornerShape(12.dp)) { Text("조작 안내", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, fontSize = 12.sp) }
            }
        }
        if (pickerOpen) {
            var episodeSearch by remember { mutableStateOf("") }
            val episodeListState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = currentEpisodeIndex.coerceAtLeast(0))
            AlertDialog(onDismissRequest = { closePicker() }, title = { Text("회차 선택") }, text = {
                Column(Modifier.heightIn(max = 360.dp)) {
                    androidx.compose.material3.OutlinedTextField(value = episodeSearch, onValueChange = { episodeSearch = it }, singleLine = true, placeholder = { Text("회차 · 제목 검색") })
                    val matching = CollectionQuery.episodes(orderedEpisodes, episodeSearch, false)
                    LaunchedEffect(episodeSearch) { episodeListState.scrollToItem(if (episodeSearch.isBlank()) currentEpisodeIndex.coerceAtLeast(0) else 0) }
                    LazyColumn(state = episodeListState) {
                        items(matching, key = { it.id }) { target ->
                            TextButton(enabled = playable(target) && !loading, onClick = { if (target.id == currentEpisode.id) closePicker() else select(target) }, modifier = Modifier.fillMaxWidth()) {
                                val progress = vm.getProgress(anime.id, target.id, target.number)
                                Text("${if (target.id == currentEpisode.id) "▶ " else ""}${target.displayNumber}화 · ${target.title}${if (progress != null && progress.progress >= .95f) " ✓" else ""}", maxLines = 2, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { closePicker() }) { Text("닫기") } })
        }
        if (helpOpen) AlertDialog(onDismissRequest = { closeHelp() }, title = { Text("플레이어 조작") }, text = {
            Text("영상 길게 누르기: 누르는 동안 2배속\n더블 탭: 좌/우 탐색\n\n외부 키보드\nSpace: 재생/정지 · 길게 눌러 2배속\n←/→: 탐색 · 0~9: 0~90% 이동\n[ / ]: 배속 · C: 자막 · Z/X: 싱크 ±0.5초\nS: OP/ED 스킵 · M: 음소거 · P: 스크린샷\nPageUp/Down: 이전/다음 화 · E: 회차 목록")
        }, confirmButton = { TextButton(onClick = { closeHelp() }) { Text("확인") } })

}
