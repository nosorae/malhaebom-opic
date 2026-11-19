package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 다시 연결은 3번까지 한다(F16.15). */
const val MAX_RECONNECTS = 3

/** 대답 없음이 이만큼이면 일시정지한다(F16.12). */
private const val SILENCE_PAUSE = 2

/** 일시정지가 이만큼 이어지면 끝낸다(F16.12). */
private val PAUSE_LIMIT = 10.minutes

/** 남은 시간이 이만큼이면 마무리로 간다(F16.20). */
private val WRAP_AT = 5.minutes

/** 남은 시간이 이만큼이면 마무리 문장 하나를 말하게 한다(F16.13). */
private val CLOSING_AT = 1.minutes

/** 2회째가 끝나고 이보다 많이 남았으면 3회째를 묻는다(F16.20). */
private val AGAIN_ABOVE = 2.minutes

/** 100만 토큰당 USD. 라이브 코치와 같은 Live 모델 단가다. 연결은 음성으로만 답해서 글 출력 단가는 두지 않는다. */
internal val INPUT_USD_PER_MILLION = mapOf("AUDIO" to 3.0, "TEXT" to 0.75)
internal val OUTPUT_USD_PER_MILLION = mapOf("AUDIO" to 12.0)

/** 같은 질문을 말할 수 있는 횟수. 10분 연습은 시간이 모자라 2회째까지다(F16.20). */
private val SpeakingState.maxTimes get() = if (planned <= 10.minutes) 2 else 3

/** 지금 상태와 일어난 일로 다음 상태를 낸다(design.md 12.1). 끝난 연습은 바뀌지 않는다. */
fun reduce(state: SpeakingState, event: SpeakingEvent): SpeakingState {
    if (state.finished) return state
    return when (event) {
        is SpeakingEvent.TimePassed -> state.timePassed(event)

        is SpeakingEvent.UserTalked -> state.copy(userTalk = state.userTalk + event.duration, silenceCount = 0, clarifying = false)

        SpeakingEvent.CoachStarted -> if (state.status == SpeakingStatus.Listening) state.copy(status = SpeakingStatus.CoachSpeaking) else state

        is SpeakingEvent.CoachTalked ->
            state.copy(coachTalk = state.coachTalk + event.played, status = if (state.status == SpeakingStatus.CoachSpeaking) SpeakingStatus.Listening else state.status)

        is SpeakingEvent.Heard -> state.copy(transcript = state.transcript.append(Line(event.speaker, event.text)))

        is SpeakingEvent.NoAnswer -> when {
            state.status != SpeakingStatus.Listening -> state
            state.clarifying -> state.copy(clarifying = false)
            state.silenceCount + 1 >= SILENCE_PAUSE -> state.copy(silenceCount = state.silenceCount + 1).pause(event.at)
            else -> state.copy(silenceCount = state.silenceCount + 1)
        }

        is SpeakingEvent.ToolCalled -> if (toolError(event.name, event.args) != null) state else state.tool(event)

        is SpeakingEvent.Judged -> state.judged(event.review)

        SpeakingEvent.ExpressionSaved -> state.copy(savedCount = state.savedCount + 1)

        is SpeakingEvent.TopicChanged -> state.copy(topic = event.topic, topicSwitches = state.topicSwitches + 1)

        is SpeakingEvent.Pause -> state.pause(event.at)

        SpeakingEvent.Resume -> state.copy(status = SpeakingStatus.Listening, pausedAt = null, silenceCount = 0, reconnects = 0)

        SpeakingEvent.Connected -> state.copy(reconnects = 0)

        is SpeakingEvent.Dropped -> state.copy(reconnects = state.reconnects + 1).let { if (it.disconnected) it.pause(event.at) else it }

        is SpeakingEvent.Used -> state.copy(usage = state.usage + event.usage)

        is SpeakingEvent.HandleReceived -> state.copy(resumeHandle = event.handle, handleAt = event.at)

        SpeakingEvent.Finish -> state.copy(finished = true)
    }
}

private fun SpeakingState.timePassed(event: SpeakingEvent.TimePassed): SpeakingState {
    if (status == SpeakingStatus.Paused) return if (pausedAt != null && event.now - pausedAt >= PAUSE_LIMIT) copy(finished = true) else this
    val next = copy(talkTime = talkTime + event.duration)
    return when {
        next.remaining <= Duration.ZERO -> next.copy(finished = true)
        next.remaining <= WRAP_AT -> next.copy(phase = SpeakingPhase.Wrap)
        else -> next
    }
}

private fun SpeakingState.pause(at: Instant) = if (status == SpeakingStatus.Paused) this else copy(status = SpeakingStatus.Paused, pausedAt = at)

private fun SpeakingState.judged(review: OralReview): SpeakingState {
    if (phase != SpeakingPhase.OralReview || review.cardId !in reviewCardIds || oralReviews.any { it.cardId == review.cardId }) return this
    val next = copy(oralReviews = oralReviews + review)
    return if (next.oralReviews.size == reviewCardIds.size) next.copy(phase = SpeakingPhase.Talk) else next
}

private fun SpeakingState.tool(event: SpeakingEvent.ToolCalled): SpeakingState = when (event.name) {
    SpeakingTools.PAUSE -> pause(event.at)

    SpeakingTools.RESUME -> reduce(this, SpeakingEvent.Resume)

    SpeakingTools.STOP -> copy(finished = true)

    SpeakingTools.CLARIFY -> copy(clarifying = true)

    SpeakingTools.FLAG_BROKEN -> if (questions.isEmpty()) this else copy(questions = questions.dropLast(1) + questions.last().copy(broken = true))

    SpeakingTools.ASK_QUESTION -> asked(event.args.text("question")!!, questionKind(event.args)!!)

    // 힌트, 이거 영어로 뭐야, 다음 질문은 코치가 말로 한다. 다음 질문은 이어 오는 ask_question 으로 적힌다.
    else -> this
}

private fun SpeakingState.asked(text: String, kind: QuestionKind): SpeakingState {
    val index = questions.indexOfFirst { it.text == text && it.topic == topic }
    if (index < 0) return copy(questions = questions + Asked(text, kind, topic))
    val same = questions[index]
    if (same.times >= maxTimes) return this
    return copy(questions = questions.toMutableList().apply { set(index, same.copy(times = same.times + 1)) })
}

/** 받아쓰기는 조각으로 온다. 같은 사람이 이어 말하면 한 줄로 잇는다. */
private fun List<Line>.append(line: Line): List<Line> {
    val last = lastOrNull()
    return if (last != null && last.speaker == line.speaker) dropLast(1) + last.copy(text = last.text + line.text) else this + line
}

/** 도구 인자가 틀렸으면 모델에게 돌려줄 에러 글, 맞으면 null. 틀린 호출은 상태를 바꾸지 않는다. */
fun toolError(name: String, args: JsonObject): String? = when (name) {
    SpeakingTools.PAUSE, SpeakingTools.RESUME, SpeakingTools.HINT, SpeakingTools.WHAT_IN_ENGLISH, SpeakingTools.NEXT_QUESTION,
    SpeakingTools.SWITCH_TOPIC, SpeakingTools.STOP, SpeakingTools.CLARIFY, SpeakingTools.FLAG_BROKEN,
    -> null

    SpeakingTools.ASK_QUESTION -> when {
        args.text("question").isNullOrBlank() -> "question is empty"
        questionKind(args) == null -> "kind must be one of ${QuestionKind.entries.joinToString { it.toolName }}"
        else -> null
    }

    SpeakingTools.JUDGE_CARD -> when {
        (args["card_id"] as? JsonPrimitive)?.longOrNull == null -> "card_id is required"
        args.text("result") !in JUDGE_RESULTS -> "result must be one of ${JUDGE_RESULTS.joinToString()}"
        else -> null
    }

    SpeakingTools.SAVE_EXPRESSION -> if (EXPRESSION_ARGS.any { args.text(it).isNullOrBlank() }) "${EXPRESSION_ARGS.joinToString()} are required" else null

    else -> "unknown tool $name"
}

/** 판정 값. 모르겠다(unknown)는 틀림과 같이 다시로 센다(F16.8). */
internal val JUDGE_RESULTS = listOf("correct", "wrong", "unknown")
private val EXPRESSION_ARGS = listOf("said", "better", "meaning", "context")

internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun questionKind(args: JsonObject) = QuestionKind.entries.firstOrNull { it.toolName == args.text("kind") }

/**
 * 바뀐 상태에서 코치에게 글로 알릴 일(design.md 12.1). 단계가 바뀌어도 지시를 다시 열지 않고 이 글로 알린다.
 * 대답 없음(F16.12), 말로 복습 끝(F16.8), 남은 5분과 2회째 뒤 3회째(F16.20), 남은 1분(F16.13)이다.
 */
fun cues(before: SpeakingState, after: SpeakingState): List<Cue> = buildList {
    if (after.finished) return@buildList
    if (before.phase == SpeakingPhase.OralReview && after.phase == SpeakingPhase.Talk) add(Cue.Talk)
    if (after.silenceCount > before.silenceCount) add(if (after.silenceCount >= SILENCE_PAUSE) Cue.Paused else Cue.Rephrase)
    if (before.phase != SpeakingPhase.Wrap && after.phase == SpeakingPhase.Wrap) add(Cue.Wrap)
    // ponytail: 2회째 뒤 사용자 말이 끝날 때마다 본다. 코치가 3회째를 묻기 전에 사용자가 또 말하면 한 번 더 알린다. 횟수 상한이 4회째를 막는다.
    if (after.phase == SpeakingPhase.Wrap && after.userTalk > before.userTalk && after.firstQuestion?.times == 2 && after.remaining > AGAIN_ABOVE && after.maxTimes == 3) add(Cue.Again)
    if (before.remaining > CLOSING_AT && after.remaining <= CLOSING_AT) add(Cue.Closing)
}

/**
 * 연습 시간에 맞는 유형(F16.6). 10분 2개, 20분 4개, 30분 5개이고 20분 이상이면 돌발 상황을 하나 넣는다.
 * [weak] 는 무너진 기록이 많은 유형이고 두 번째로 묻는다. 나머지는 묘사, 지난 경험, 미래 계획, 비교, 습관 순이다.
 */
fun questionKinds(planned: Duration, weak: QuestionKind?): List<QuestionKind> {
    val count = when {
        planned <= 10.minutes -> 2
        planned <= 20.minutes -> 4
        else -> 5
    }
    val unexpected = planned >= 20.minutes
    val picked = (listOf(QuestionKind.Description) + listOfNotNull(weak) + QuestionKind.entries).distinct()
        .filter { unexpected || it != QuestionKind.Unexpected }
        .take(count)
    return if (!unexpected || QuestionKind.Unexpected in picked) picked else picked.dropLast(1) + QuestionKind.Unexpected
}

/** 지난 연습들의 다룬 질문에서 무너진 질문이 가장 많은 유형. 없으면 null 이다(F16.6). */
fun weakKind(questions: List<Asked>): QuestionKind? = questions.filter { it.broken }.groupingBy { it.kind }.eachCount().maxByOrNull { it.value }?.key

/** 고칠 표현 카드에서 자주 틀리는 유형 셋(design.md 12.1). */
fun frequentErrors(cards: List<ExpressionCard>): List<ErrorType> = cards.mapNotNull { it.errorType }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
