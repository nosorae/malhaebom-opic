package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject

/** 리허설 녹음을 끝내고 받아쓰기를 받아 피드백 단계로 넘긴다(F13.1, F13.2). 녹음을 멈추지 못했으면 받아쓰기를 보내지 않는다. */
@Inject
class FinishRehearsalUseCase(private val stopRehearsalUseCase: StopRehearsalUseCase, private val transcribeRehearsalUseCase: TranscribeRehearsalUseCase) {
    suspend operator fun invoke(sessionId: String): RehearsalError? = stopRehearsalUseCase() ?: transcribeRehearsalUseCase(sessionId)
}
