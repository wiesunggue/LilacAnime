package com.lilac.anime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lilac.anime.core.concurrent.LatestRequest
import kotlinx.coroutines.*

/** A visible, cancellable result surface; failures cannot silently reset a button. */
@Composable
fun TranslationTestPanel(provider: String, timeoutMs: Long = 60000L, execute: suspend (String) -> Result<String>) {
    val scope = rememberCoroutineScope()
    val requests = remember { LatestRequest(scope) }
    val currentExecute by rememberUpdatedState(execute)
    var text by remember { mutableStateOf("こんにちは、今日はいい天気ですね。") }
    var open by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var timer by remember { mutableStateOf<Job?>(null) }
    fun cancel() {
        requests.cancel(); timer?.cancel(); running = false
        title = "번역 테스트 취소"; result = "요청을 취소했습니다."
    }
    DisposableEffect(Unit) { onDispose { requests.cancel(); timer?.cancel() } }
    Column {
        Text("번역 테스트 · $provider")
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("번역할 문장") }, enabled = !running, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !running && text.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            val input = text
            title = "번역 테스트 진행 중 · $provider"
            result = "응답을 기다리고 있습니다. 최대 ${timeoutMs / 1000}초 후 결과 또는 오류를 표시합니다."
            open = true; running = true
            requests.start {
                timer = scope.launch {
                    delay(timeoutMs)
                    requests.cancel(); running = false
                    title = "번역 테스트 실패"
                    result = "응답 시간이 초과되었습니다. 네트워크, API 사용량 또는 로컬 모델 실행 상태를 확인하세요."
                }
                try {
                    val response = withContext(Dispatchers.IO) { currentExecute(input) }.getOrThrow()
                    checkActive()
                    require(response.isNotBlank()) { "번역 결과가 비어 있습니다." }
                    require(response.trim() != input.trim()) { "번역 결과가 원문과 같습니다. 모델이 번역문을 반환하지 않았습니다." }
                    title = "번역 테스트 성공"
                    result = response
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    checkActive()
                    title = "번역 테스트 실패"
                    result = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                } finally {
                    if (isCurrent) { timer?.cancel(); running = false }
                }
            }
        }) { Text(if (running) "번역 테스트 중..." else "번역 테스트 실행") }
        if (!open && result.isNotEmpty()) Text("$title\n$result")
    }
    if (open) AlertDialog(
        onDismissRequest = { if (running) cancel(); open = false },
        title = { Text(title) },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(result)
        } } },
        confirmButton = { TextButton(onClick = { if (running) cancel() else open = false }) { Text(if (running) "취소" else "닫기") } },
    )
}
