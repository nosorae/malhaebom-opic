package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 내 목소리 크기와 코치 목소리 크기(0~1). 지금 말하는 쪽의 목소리 띠에 쓴다(F12.1). */
@Inject
class ObserveCoachLevelsUseCase(private val audio: DuplexAudio) {
    operator fun invoke(): Flow<Pair<Float, Float>> = combine(audio.micLevel, audio.playbackLevel, ::Pair)
}
