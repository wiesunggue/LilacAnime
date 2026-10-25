package com.lilac.anime.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.player.PlayerScreen
import com.lilac.anime.viewmodel.AnimeViewModel
import kotlinx.coroutines.CancellationException

/** Resolves saved history/episode IDs after process restart as well as in-session. */
@Composable
fun PlaybackEntryScreen(vm: AnimeViewModel, animeId: String?, requestedKey: String?, back: () -> Unit) {
    val context = LocalContext.current
    var target by remember(animeId, requestedKey) { mutableStateOf<Pair<Anime, Episode>?>(null) }
    var error by remember(animeId, requestedKey) { mutableStateOf<String?>(null) }
    var loading by remember(animeId, requestedKey) { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(animeId, requestedKey, attempt, vm.watchHistoryLoaded) {
        if (!vm.watchHistoryLoaded) return@LaunchedEffect
        loading = true; error = null
        try {
            require(!animeId.isNullOrBlank()) { "작품 정보가 없습니다." }
            target = vm.resolvePlaybackTarget(context, animeId, requestedKey)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "회차를 불러오지 못했습니다." }
        finally { loading = false }
    }
    val resolved = target
    if (resolved != null) PlayerScreen(anime = resolved.first, episode = resolved.second, vm = vm, back = back)
    else FullScreenState(message = error ?: "이어볼 회차를 준비하는 중…", isLoading = loading,
        onRetry = if (error != null) ({ attempt++ }) else null, onBack = back)
}
