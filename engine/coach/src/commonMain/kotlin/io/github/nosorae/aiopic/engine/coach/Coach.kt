package io.github.nosorae.aiopic.engine.coach

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Instant

/** 세션 하나는 질문 하나다. 경험 끌어내기, 표현 코칭, 리허설, 피드백을 지나 스크립트 정리로 끝난다(F11~F14). */
enum class CoachPhase { Explore, Express, Rehearse, Feedback, Script }

/** 저장하는 세션 상태. 연결이 끊긴 것은 [CoachState.disconnected] 로 따로 둔다. */
enum class CoachStatus { Active, Paused, Finished }

enum class Speaker { User, Coach }

/** `save_material` 의 kind 값은 이 이름의 소문자다. */
enum class MaterialKind { Place, Person, Event, Feeling }

enum class Modality { Audio, Text }

@Serializable
data class Line(val phase: CoachPhase, val speaker: Speaker, val text: String)

@Serializable
data class Material(val kind: MaterialKind, val text: String)

@Serializable
data class Expression(val userSaid: String, val english: String, val grade: String)

/** 한 단계에서 쓴 토큰. 모달리티마다 단가가 달라 나눠 센다. */
@Serializable
data class TokenUsage(val input: Map<Modality, Int> = emptyMap(), val output: Map<Modality, Int> = emptyMap()) {
    val inputTokens: Int get() = input.values.sum()
    val outputTokens: Int get() = output.values.sum()
    val costUsd: Double get() = cost(input, INPUT_USD_PER_MILLION) + cost(output, OUTPUT_USD_PER_MILLION)

    operator fun plus(other: TokenUsage) = TokenUsage(input.merge(other.input), output.merge(other.output))
}

private fun Map<Modality, Int>.merge(other: Map<Modality, Int>) = (keys + other.keys).associateWith { (this[it] ?: 0) + (other[it] ?: 0) }

private fun cost(tokens: Map<Modality, Int>, prices: Map<Modality, Double>) = tokens.entries.sumOf { (modality, count) -> count * (prices[modality] ?: 0.0) / 1_000_000 }

/**
 * 코치 세션 상태. [reduce] 가 일어난 일로 다음 상태를 낸다. 받아쓰기, 메모, 사용량은 저장한 이벤트로 다시 계산하고
 * 단계, 대화 시간, 핸들, 상태는 세션 행에 둔다.
 */
data class CoachState(
    val id: String,
    val questionId: String,
    val targetGrade: String,
    val startedAt: Instant,
    val phase: CoachPhase = CoachPhase.Explore,
    val status: CoachStatus = CoachStatus.Active,
    /** 연결이 끊긴 이유. null 이 아니면 화면이 "다시 연결" 을 보인다. */
    val disconnected: String? = null,
    val talkTime: Duration = Duration.ZERO,
    val resumeHandle: String? = null,
    val handleAt: Instant? = null,
    val lines: List<Line> = emptyList(),
    val materials: List<Material> = emptyList(),
    val expressions: List<Expression> = emptyList(),
    val rehearsals: List<String> = emptyList(),
    val usage: Map<CoachPhase, TokenUsage> = emptyMap(),
) {
    /** 25분이 넘어 곧 정리한다고 안내한다. */
    val timeWarning: Boolean get() = talkTime >= TALK_WARNING && status != CoachStatus.Finished

    val costUsd: Double get() = usage.values.sumOf { it.costUsd }
}

/** 팩이 넘기는 코치 설정. 단계별 시스템 지시와 도구 선언이다. 도구 이름은 [CoachTools] 를 쓴다. */
class CoachProfile(val instructions: Map<CoachPhase, String>, val tools: List<JsonObject>)

object CoachTools {
    const val ADVANCE_PHASE = "advance_phase"
    const val SAVE_MATERIAL = "save_material"
    const val SAVE_EXPRESSION = "save_expression"
}

/** 세션이 만든 스크립트. 만드는 일은 [ScriptWriter] 다(#267). [edited] 는 사용자가 고친 판이다. */
data class CoachScript(
    val sessionId: String,
    val intro: String,
    val body: String,
    val closing: String,
    val expressions: List<Expression>,
    val edited: String?,
    val editedAt: Instant?,
    val promptVersion: String,
    val model: String,
)

/** 코치 세션에 일어난 일. 받아쓰기, 도구 호출, 사용량, 단계 전환은 저장해 두었다가 앱을 다시 켜면 다시 계산한다. */
@Serializable
sealed interface CoachEvent {
    @Serializable
    data class Heard(val speaker: Speaker, val text: String) : CoachEvent

    @Serializable
    data class ToolCalled(val name: String, val args: JsonObject) : CoachEvent

    @Serializable
    data class Used(val usage: TokenUsage) : CoachEvent

    /** 사용자가 "다음 단계" 를 골랐다. */
    @Serializable
    data object NextPhase : CoachEvent

    /** 리허설 녹음의 받아쓰기(F13). 녹음은 화면이 기존 녹음기로 받는다. */
    @Serializable
    data class Rehearsed(val text: String) : CoachEvent

    @Serializable
    data class TimePassed(val duration: Duration) : CoachEvent

    @Serializable
    data object Paused : CoachEvent

    /** 일시정지나 끊김에서 다시 연결한다. */
    @Serializable
    data object Resumed : CoachEvent

    @Serializable
    data class Dropped(val reason: String) : CoachEvent

    @Serializable
    data class HandleReceived(val handle: String, val at: Instant) : CoachEvent
}
