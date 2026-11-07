package io.github.nosorae.aiopic.ui.featurepractice

import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.libs.recorder.api.RecordingFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 녹음 시작 뒤 다시 듣기를 할 수 있는 시간. 모의고사와 같은 규칙이다(F2.4, F4.1). */
val ReplayWindow = 5.seconds

/** 녹음 시작 뒤 Next 가 켜지기까지의 시간(F2.4, F4.1). */
val NextDelay = 10.seconds

data class PracticeUiState(
    /** 낼 질문. 고르기 전이거나 고르지 못했으면 null 이다. 글자는 화면에 보이지 않는다. */
    val question: Question? = null,
    /** 왜 이 질문인지. 고른 약한 유형과 점수다. 분석한 답변이 없어 전체에서 골랐으면 null 이다. */
    val weakness: PracticeWeakness? = null,
    val phase: PracticePhase = PracticePhase.Ready,
    /** 지금 녹음이 시작된 뒤 흐른 시간. */
    val recorded: Duration = Duration.ZERO,
    /** 녹음 중 마이크 음량(0~1). 녹음 중이 아니면 0 이다. */
    val voiceLevel: Float = 0f,
    val replayUsed: Boolean = false,
    val error: PracticeError? = null,
    /** 누른 버튼(Next, 나가기)의 저장이 도는 중이다. 끝날 때까지 그 버튼은 진행 표시를 보이고 다른 입력은 받지 않는다. */
    val saving: Boolean = false,
    /** 그 저장이 Next 로 낸 답변이다. 이때만 Next 가 진행 표시를 보이고 단계가 피드백으로 넘어간다. 나가기의 저장은 아니다. */
    val submitting: Boolean = false,
    val navigation: PracticeDestination? = null,
    /** 스크립트로 연습할 때 녹음 화면에 접었다 펴는 스크립트 글(F14.5). 아니면 null 이다. */
    val script: String? = null,
) {
    val canPlay: Boolean get() = question != null && phase == PracticePhase.Ready

    /** 질문을 고르는 중이거나 질문을 읽는 중이다. Play 가 진행 표시를 보인다. */
    val preparing: Boolean get() = (question == null && error == null) || phase == PracticePhase.Speaking
    val canReplay: Boolean get() = phase == PracticePhase.Recording && !replayUsed && recorded < ReplayWindow && !saving
    val canNext: Boolean get() = phase == PracticePhase.Recording && recorded >= NextDelay
}

/** 질문 글자는 보이지 않고 읽기와 녹음만 있다. */
enum class PracticePhase { Ready, Speaking, Recording }

enum class PracticeError {
    /** 사용자가 마이크 권한을 주지 않았다. 설정에서 켜야 한다. */
    MicDenied,

    /** 권한은 있는데 마이크를 열지 못했다. 다른 앱이 마이크를 쓰고 있을 때 생긴다. */
    MicUnavailable,

    /** 녹음 조각 파일을 만들거나 쓰지 못했다. 저장 공간이 모자랄 때 생긴다. */
    RecordingFailed,

    /** 기기 음성으로 질문을 읽지 못했다. */
    SpeechFailed,

    /** 문항 풀이 비어 낼 질문이 없다. */
    QuestionUnavailable,

    /** 스크립트의 질문이 문항 풀에 없다. 서베이 주제를 바꿔 빠진 질문이다(F14.5). */
    ScriptQuestionMissing,
}

internal fun RecordingFailure.toPracticeError(): PracticeError = when (this) {
    RecordingFailure.MicUnavailable -> PracticeError.MicUnavailable
    RecordingFailure.WriteFailed -> PracticeError.RecordingFailed
}

/** 저장이 끝나야 가는 이동만 상태로 둔다(ADR 0009). 어디로 갈지는 화면을 등록한 쪽이 정한다. */
sealed interface PracticeDestination {
    /** 답변을 남기지 않고 나간다. */
    data object Exit : PracticeDestination

    /** 답변을 저장했다. 이 답변의 리포트를 바로 연다(F4.2, F7.1). */
    data class Feedback(val answerId: String) : PracticeDestination
}

sealed interface PracticeIntent {
    data object PlayQuestion : PracticeIntent

    data object ReplayQuestion : PracticeIntent

    data object SubmitAnswer : PracticeIntent

    /** 화면이 마이크 권한을 요청했는데 거절됐다. */
    data object MicDenied : PracticeIntent

    data object ExitPractice : PracticeIntent

    data object NavigationHandled : PracticeIntent
}
