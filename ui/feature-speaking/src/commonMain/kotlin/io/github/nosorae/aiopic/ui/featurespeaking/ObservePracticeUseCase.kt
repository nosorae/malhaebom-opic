package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 지금 도는 연습과 목소리 크기. 연습이 없으면 [state] 가 null 이다. */
data class PracticeView(val state: SpeakingState?, val level: Float)

@Inject
class ObservePracticeUseCase(private val control: PracticeControl) {
    operator fun invoke(): Flow<PracticeView> = combine(control.state, control.level, ::PracticeView)
}
