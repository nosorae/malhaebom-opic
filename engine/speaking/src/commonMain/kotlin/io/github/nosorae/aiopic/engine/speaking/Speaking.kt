package io.github.nosorae.aiopic.engine.speaking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Instant

/** 연습 단계. 말로 복습(F16.8), 대화(F16.5, F16.6), 남은 5분부터 마무리(F16.20)다. */
enum class SpeakingPhase { OralReview, Talk, Wrap }

/** 일시정지에는 대답 없음, 명령, 오디오 끼어듦, 다시 연결 실패가 모두 든다. 연결이 끊긴 것은 [SpeakingState.disconnected] 로 본다. */
enum class SpeakingStatus { Listening, CoachSpeaking, Paused }

enum class AudioRoute { Earphones, Speaker }

enum class Speaker { User, Coach }

/** 주제 안에서 묻는 유형 여섯(F16.6). 도구 인자 값은 이름의 스네이크 표기([toolName])다. */
enum class QuestionKind {
    Description,
    PastExperience,
    FuturePlan,
    Comparison,
    Routine,
    Unexpected,
    ;

    val toolName: String get() = name.snake()
}

/** `PastExperience` 를 `past_experience` 로 바꾼다. 도구 인자와 지시 글에 쓴다. */
internal fun String.snake() = replace(Regex("(?<=.)[A-Z]")) { "_${it.value}" }.lowercase()

@Serializable
data class Line(val speaker: Speaker, val text: String)

/** 다룬 질문 하나. [times] 는 같은 질문을 말한 횟수(F16.20), [broken] 은 문단이 끊기거나 시제가 무너졌는지다(F16.6). */
@Serializable
data class Asked(val text: String, val kind: QuestionKind, val topic: String, val times: Int = 1, val broken: Boolean = false)

/** 판정 전 카드 복습 값(F16.8). 판정을 바꿀 때 이 값으로 되돌린 뒤 다시 계산한다(F17.10). 시각은 epoch 밀리초다. */
@Serializable
data class ReviewValues(val difficulty: Double?, val stability: Double?, val dueAtMs: Long, val lastReviewedAtMs: Long?)

@Serializable
data class OralReview(val cardId: Long, val better: String, val correct: Boolean, val judgedAtMs: Long, val before: ReviewValues)

/** 연결이 쓴 토큰. 키는 서버의 모달리티 이름(`AUDIO`, `TEXT`)이다. 연결 어댑터가 턴별 값으로 맞춰 넘긴다. */
@Serializable
data class Usage(val input: Map<String, Int> = emptyMap(), val output: Map<String, Int> = emptyMap()) {
    val costUsd: Double get() = cost(input, INPUT_USD_PER_MILLION) + cost(output, OUTPUT_USD_PER_MILLION)

    operator fun plus(other: Usage) = Usage(input.merge(other.input), output.merge(other.output))
}

private fun Map<String, Int>.merge(other: Map<String, Int>) = (keys + other.keys).associateWith { (this[it] ?: 0) + (other[it] ?: 0) }

private fun cost(tokens: Map<String, Int>, prices: Map<String, Double>) = tokens.entries.sumOf { (modality, count) -> count * (prices[modality] ?: 0.0) / 1_000_000 }

/**
 * 진행 중인 연습(F16.16). [reduce] 가 일어난 일로 다음 상태를 낸다. [clarifying] 과 [finished] 는 표에 두지 않는다.
 * 앱이 죽었다 살아나면 확인 질문 하나를 잊고, 끝난 연습은 연습 기록으로 옮겨져 있다.
 */
data class SpeakingState(
    val id: String,
    val startedAt: Instant,
    val planned: Duration,
    val route: AudioRoute,
    val topic: String,
    val reviewCardIds: List<Long> = emptyList(),
    val phase: SpeakingPhase = if (reviewCardIds.isEmpty()) SpeakingPhase.Talk else SpeakingPhase.OralReview,
    val status: SpeakingStatus = SpeakingStatus.Listening,
    val pausedAt: Instant? = null,
    /** 대화 시간. 일시정지 동안 늘지 않는다(F16.13). */
    val talkTime: Duration = Duration.ZERO,
    val userTalk: Duration = Duration.ZERO,
    val coachTalk: Duration = Duration.ZERO,
    val silenceCount: Int = 0,
    /** 이어서 실패한 다시 연결 횟수. 연결에 성공하면 0 이다. */
    val reconnects: Int = 0,
    val topicSwitches: Int = 0,
    val questions: List<Asked> = emptyList(),
    val transcript: List<Line> = emptyList(),
    val savedCount: Int = 0,
    val resumeHandle: String? = null,
    val handleAt: Instant? = null,
    val usage: Usage = Usage(),
    val oralReviews: List<OralReview> = emptyList(),
    /** 코치가 확인 질문을 했다. 그 뒤의 대답 없음은 세지 않는다(F16.21). */
    val clarifying: Boolean = false,
    val finished: Boolean = false,
) {
    val remaining: Duration get() = planned - talkTime

    /** 다시 연결을 3번 넘게 실패했다. 진행 알림이 다시 연결 버튼을 보인다(F16.15). */
    val disconnected: Boolean get() = reconnects > MAX_RECONNECTS

    /** 지금 주제의 첫 질문. 마무리에서 다시 묻는다(F16.20). */
    val firstQuestion: Asked? get() = questions.firstOrNull { it.topic == topic }
}

/** 연습에 일어난 일(design.md 12.1). 시각이 필요한 일은 시각을 싣고 와서 [reduce] 가 시계를 모른다. */
sealed interface SpeakingEvent {
    /** 대화 시계가 [duration] 만큼 흘렀다. 일시정지 중에는 [now] 로 일시정지 10분만 본다. */
    data class TimePassed(val duration: Duration, val now: Instant) : SpeakingEvent

    /** 기기 말소리 감지로 잰 사용자 말 한 마디가 끝났다(F16.24). */
    data class UserTalked(val duration: Duration) : SpeakingEvent

    data object CoachStarted : SpeakingEvent

    /** 코치 음성 재생이 끝나거나 끊겼다. [played] 는 실제로 재생한 길이다(F16.24). */
    data class CoachTalked(val played: Duration) : SpeakingEvent

    data class Heard(val speaker: Speaker, val text: String) : SpeakingEvent

    /** 코치가 말한 뒤 사용자가 대답하지 않았다(F16.12). */
    data class NoAnswer(val at: Instant) : SpeakingEvent

    /** 상태만 바꾸는 코치 도구 호출. 카드와 주제를 고치는 도구는 실행 쪽이 처리하고 [Judged], [ExpressionSaved], [TopicChanged] 로 넣는다. */
    data class ToolCalled(val name: String, val args: JsonObject, val at: Instant) : SpeakingEvent

    data class Judged(val review: OralReview) : SpeakingEvent

    data object ExpressionSaved : SpeakingEvent

    data class TopicChanged(val topic: String) : SpeakingEvent

    /** 화면 버튼, 알림 버튼, 이어폰 버튼, 오디오 끼어듦(F16.10, F16.14). */
    data class Pause(val at: Instant) : SpeakingEvent

    /** 다시 시작. 끊김으로 멈췄으면 다시 연결도 된다(F16.15). */
    data object Resume : SpeakingEvent

    data object Connected : SpeakingEvent

    data class Dropped(val at: Instant) : SpeakingEvent

    data class Used(val usage: Usage) : SpeakingEvent

    data class HandleReceived(val handle: String, val at: Instant) : SpeakingEvent

    /** 어디서 끝내든 이 하나다(F16.11). */
    data object Finish : SpeakingEvent
}

/** 코치 도구 이름. 명령 일곱(F16.9)과 질문, 확인 질문, 무너짐 표시, 말로 복습 판정, 몰랐던 표현 저장이다. 선언은 시험 팩이 한다. */
object SpeakingTools {
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val HINT = "hint"
    const val WHAT_IN_ENGLISH = "what_in_english"
    const val NEXT_QUESTION = "next_question"
    const val SWITCH_TOPIC = "switch_topic"
    const val STOP = "stop"
    const val ASK_QUESTION = "ask_question"
    const val CLARIFY = "clarify"
    const val FLAG_BROKEN = "flag_broken"
    const val JUDGE_CARD = "judge_card"
    const val SAVE_EXPRESSION = "save_expression"
}

/** 실행 쪽이 코치에게 글로 알리는 일. 글은 시험 팩의 [SpeakingProfile.cues] 이고 `{{question}}` 에 지금 주제의 첫 질문이 들어간다. */
enum class Cue { Talk, Rephrase, Paused, Wrap, Again, Closing }
