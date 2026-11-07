package io.github.nosorae.aiopic.ui.featurelive

/**
 * 실시간 음성 전송 동의(F15.1). 녹음 파일 전송 동의(F1.3)와 따로 저장한다. 실제 저장은 앱 층이 플랫폼 설정 저장소로 넣는다.
 * 동의 전에는 코치 세션을 시작하지 않는다.
 */
interface LiveConsent {
    suspend fun isLiveGiven(): Boolean

    suspend fun giveLive()
}
