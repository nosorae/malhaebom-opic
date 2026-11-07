package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.coach.CoachEvent
import io.github.nosorae.aiopic.engine.session.model.AnalysisError

/**
 * 리허설 녹음의 받아쓰기를 받아 [sessionId] 세션에 넣고 피드백 단계로 넘긴다(F13.1, F13.2). 엔진이 그 글을 피드백 지시에 넣어 새로 연결한다.
 * 받으면 녹음을 지운다. 리허설 녹음은 리포트처럼 다시 듣는 곳이 없다. 못 받으면 다시 시도하게 녹음을 남긴다.
 */
@Inject
class TranscribeRehearsalUseCase(private val files: RehearsalFiles, private val coach: RunningCoach) {
    suspend operator fun invoke(sessionId: String): RehearsalError? {
        val directory = files.directory(sessionId)
        val text = when (val result = files.transcribe(directory)) {
            is AnalysisResult.Success -> result.value.text.takeIf { it.isNotBlank() } ?: return RehearsalError.NoSpeech
            is AnalysisResult.Unscorable -> return RehearsalError.NoSpeech
            is AnalysisResult.Failed -> return if (result.error == AnalysisError.InvalidKey) RehearsalError.InvalidKey else RehearsalError.TranscriptFailed
        }
        coach.send(sessionId, CoachEvent.Rehearsed(text))
        files.clear(directory)
        return null
    }
}
