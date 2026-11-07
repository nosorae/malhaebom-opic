package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.libs.recorder.api.Recorder

/** 녹음을 멈춘다. 녹음 중에 화면을 떠날 때도 쓴다. */
@Inject
class StopRehearsalUseCase(private val recorder: Recorder) {
    suspend operator fun invoke(): RehearsalError? = recorder.stop().failure?.toRehearsalError()
}
