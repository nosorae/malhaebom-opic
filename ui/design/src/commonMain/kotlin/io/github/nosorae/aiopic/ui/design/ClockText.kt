package io.github.nosorae.aiopic.ui.design

import kotlin.time.Duration

/** 녹음 시간과 남은 시간을 "1:03" 처럼 분:초로 보인다. 음수는 0초다. */
fun Duration.clockText(): String {
    val seconds = inWholeSeconds.coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
