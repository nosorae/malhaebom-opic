package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.Transcript
import io.github.nosorae.aiopic.libs.recorder.api.RecordingFailure
import kotlin.time.Duration

/** 리허설 단계 화면(F13.1). 코치 연결은 닫혀 있고 기존 녹음기로 처음부터 끝까지 한 번 말한다. */
data class RehearsalUiState(
    val step: RehearsalStep = RehearsalStep.Ready,
    /** 녹음이 시작된 뒤 흐른 시간. */
    val recorded: Duration = Duration.ZERO,
    /** 녹음 중 마이크 음량(0~1). */
    val voiceLevel: Float = 0f,
    val error: RehearsalError? = null,
) {
    /** 녹음은 남아 있고 받아쓰기만 못 받았다. 다시 시도하면 같은 녹음을 다시 보낸다. */
    val canRetryTranscript: Boolean get() = step == RehearsalStep.Ready && (error == RehearsalError.InvalidKey || error == RehearsalError.TranscriptFailed)
}

enum class RehearsalStep { Ready, Recording, Transcribing }

enum class RehearsalError {
    /** 다른 앱이 마이크를 쓰고 있어 열지 못했다. */
    MicUnavailable,

    /** 녹음 조각을 쓰지 못했다. 저장 공간이 모자랄 때 생긴다. */
    RecordingFailed,

    /** 받아쓸 말이 없었다. 다시 녹음한다. */
    NoSpeech,

    /** 키가 없거나 틀렸다. 설정에서 고친 뒤 다시 시도한다. */
    InvalidKey,

    /** 네트워크, 한도 같은 이유로 받아쓰기를 못 받았다. */
    TranscriptFailed,
}

sealed interface RehearsalIntent : LiveCoachIntent {
    data object StartRecording : RehearsalIntent

    /** 녹음을 끝내고 받아쓰기를 받는다. 받으면 피드백 단계로 간다. */
    data object StopRecording : RehearsalIntent

    data object RetryTranscript : RehearsalIntent

    /** 피드백 단계에서 리허설 단계로 돌아간다(F13.3). */
    data object RehearseAgain : RehearsalIntent
}

/** 리허설 녹음 파일. 앱 층이 녹음 폴더 위치, 조각 이어 붙이기와 파일 읽기, 받아쓰기 포트를 묶는다. */
interface RehearsalFiles {
    /** 세션의 리허설 녹음 폴더. */
    fun directory(sessionId: String): String

    /** 폴더의 녹음 조각을 이어 붙여 받아쓰기만 받는다(F13.1). 답변 분석은 돌리지 않는다. */
    suspend fun transcribe(directory: String): AnalysisResult<Transcript>

    /** 폴더의 녹음을 지운다. */
    suspend fun clear(directory: String)
}

internal fun RecordingFailure.toRehearsalError() = when (this) {
    RecordingFailure.MicUnavailable -> RehearsalError.MicUnavailable
    RecordingFailure.WriteFailed -> RehearsalError.RecordingFailed
}
