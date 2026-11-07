package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.libs.recorder.api.Recorder
import kotlinx.coroutines.flow.Flow

/** 리허설 녹음 중 마이크 음량(0~1). */
@Inject
class ObserveRehearsalLevelUseCase(private val recorder: Recorder) {
    operator fun invoke(): Flow<Float> = recorder.levels
}
