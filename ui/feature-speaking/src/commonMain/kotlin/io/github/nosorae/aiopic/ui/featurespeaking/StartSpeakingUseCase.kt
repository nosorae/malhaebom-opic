package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.ui.featurelive.LiveConsent
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 시트에서 고른 시간과 소리 환경으로 연습을 시작한다(F16.2). 고른 값은 이번 연습에만 쓰고 설정에 남기지 않는다.
 * 동의 화면 대신 시트의 "동의하고 시작" 이 실시간 음성 전송 동의(F15.1)를 남긴다. 마이크 권한은 화면이 먼저 받는다.
 */
@Inject
class StartSpeakingUseCase(private val control: PracticeControl, private val settings: SpeakingSettings, private val consent: LiveConsent) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(minutes: Int, route: AudioRoute) {
        if (!consent.isLiveGiven()) consent.giveLive()
        control.start(Uuid.random().toString(), settings.setup(minutes.minutes, route))
    }
}
