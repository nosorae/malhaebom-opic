package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject

/**
 * 실시간 음성 전송 동의(F15.1). 녹음 파일 전송 동의(F1.3)와 따로 저장한다. 실제 저장은 앱 층이 플랫폼 설정 저장소로 넣는다.
 * 동의 전에는 코치 세션을 시작하지 않는다.
 */
interface LiveConsent {
    suspend fun isLiveGiven(): Boolean

    suspend fun giveLive()
}

@Inject
class CheckLiveConsentUseCase(private val consent: LiveConsent) {
    suspend operator fun invoke(): Boolean = consent.isLiveGiven()
}

@Inject
class GiveLiveConsentUseCase(private val consent: LiveConsent) {
    suspend operator fun invoke() = consent.giveLive()
}
