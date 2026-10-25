package com.lilac.anime.data.offline

/** State decisions shared by service recovery and the download manager UI. */
internal object DownloadQueuePolicy {
    fun shouldRecover(state: String): Boolean = state == "queued" || state == "downloading"
    fun canPause(state: String): Boolean = shouldRecover(state)
    fun canResume(state: String, sourceUrl: String?): Boolean = state in setOf("paused", "failed") && !sourceUrl.isNullOrBlank()
    fun label(state: String): String = when (state) {
        "queued" -> "대기 중"
        "downloading" -> "다운로드 중"
        "paused" -> "일시정지"
        "failed" -> "실패"
        "completed" -> "완료"
        "cancelled" -> "취소됨"
        else -> "상태 확인 필요"
    }
}
