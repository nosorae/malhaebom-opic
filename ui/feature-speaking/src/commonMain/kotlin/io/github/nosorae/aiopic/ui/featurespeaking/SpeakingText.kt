package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.speaking.Usage

/** Live 음성 1초의 토큰 수. */
private const val AUDIO_TOKENS_PER_SECOND = 32

/**
 * 고른 시간의 대략 비용(F16.2). 마이크 소리를 대화 내내 입력으로 보내고 코치가 절반을 말한다고 보고 유료 등급 단가로 센다.
 * ponytail: 대화 맥락이 턴마다 다시 입력으로 세지는 몫은 넣지 않는다. 연습 기록의 사용량이 쌓이면 분당 평균으로 바꾼다.
 */
internal fun estimatedCostUsd(minutes: Int): Double {
    val seconds = minutes * 60
    return Usage(input = mapOf("AUDIO" to seconds * AUDIO_TOKENS_PER_SECOND), output = mapOf("AUDIO" to seconds / 2 * AUDIO_TOKENS_PER_SECOND)).costUsd
}
