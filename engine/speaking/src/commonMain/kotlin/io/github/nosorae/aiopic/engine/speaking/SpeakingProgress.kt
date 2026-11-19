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

        is SpeakingEvent.UserTalked ->
            if (state.status == SpeakingStatus.Paused) state else state.copy(userTalk = state.userTalk + event.duration, silenceCount = 0, clarifying = false, answered = true)

        SpeakingEvent.CoachStarted -> if (state.status == SpeakingStatus.Listening) state.copy(status = SpeakingStatus.CoachSpeaking) else state

        is SpeakingEvent.CoachTalked ->
            state.copy(coachTalk = state.coachTalk + event.played, status = if (state.status == SpeakingStatus.CoachSpeaking) SpeakingStatus.Listening else state.status)
                .let { if (it.againDue) it.copy(againSent = true) else it }

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

        is SpeakingEvent.TopicChanged -> state.copy(topic = event.topic, topicSwitches = state.topicSwitches + 1, current = null, answered = false)

        is SpeakingEvent.Pause -> state.pause(event.at)

        SpeakingEvent.Resume -> if (state.status != SpeakingStatus.Paused) state else state.copy(status = SpeakingStatus.Listening, pausedAt = null, silenceCount = 0, reconnects = 0)

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

/** 말로 복습 중에 마무리로 넘어가도 늦게 온 판정은 받아 FSRS 에 반영한다. 단계는 말로 복습일 때만 대화로 바꾼다. */
private fun SpeakingState.judged(review: OralReview): SpeakingState {
    if (review.cardId !in reviewCardIds || oralReviews.any { it.cardId == review.cardId }) return this
    val next = copy(oralReviews = oralReviews + review)
    return if (phase == SpeakingPhase.OralReview && next.oralReviews.size == reviewCardIds.size) next.copy(phase = SpeakingPhase.Talk) else next
}

/** 마무리에서 첫 질문을 2회째 묻고 사용자가 대답한 뒤 코치 턴이 끝났다. 2분 넘게 남았고 10분 연습이 아니면 3회째를 묻게 한다(F16.20). */
private val SpeakingState.againDue: Boolean
    get() {
        val first = firstQuestion ?: return false
        return phase == SpeakingPhase.Wrap && !againSent && answered && current == questions.indexOf(first) && first.times == 2 && remaining > AGAIN_ABOVE && maxTimes == 3
    }

private fun SpeakingState.tool(event: SpeakingEvent.ToolCalled): SpeakingState = when (event.name) {
    SpeakingTools.PAUSE -> pause(event.at)

    SpeakingTools.RESUME -> reduce(this, SpeakingEvent.Resume)

    SpeakingTools.STOP -> copy(finished = true)

    SpeakingTools.CLARIFY -> copy(clarifying = true)

    SpeakingTools.FLAG_BROKEN -> current?.let { copy(questions = questions.toMutableList().apply { set(it, get(it).copy(broken = true)) }) } ?: this

    SpeakingTools.ASK_QUESTION -> asked(event.args.text("question")!!, questionKind(event.args)!!)

    // 힌트, 이거 영어로 뭐야, 다음 질문은 코치가 말로 한다. 다음 질문은 이어 오는 ask_question 으로 적힌다.
    else -> this
}

private fun SpeakingState.asked(text: String, kind: QuestionKind): SpeakingState {
    val index = questions.indexOfFirst { it.text == text && it.topic == topic }
    if (index < 0) return copy(questions = questions + Asked(text, kind, topic), current = questions.size, answered = false)
    val same = questions[index]
    if (same.times >= maxTimes) return this
    return copy(questions = questions.toMutableList().apply { set(index, same.copy(times = same.times + 1)) }, current = index, answered = false)
}

/** 같은 질문을 상한만큼 말했으면 코치에게 돌려줄 에러 글. 상한을 넘는 질문은 기록하지 않으므로 코치가 알게 한다(F16.20). */
fun askError(state: SpeakingState, args: JsonObject): String? {
    val same = state.questions.firstOrNull { it.text == args.text("question") && it.topic == state.topic } ?: return null
    return if (same.times >= state.maxTimes) "asked ${same.times} times already" else null
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
 * 대답 없음과 일시정지(F16.12), 다시 시작(F16.14), 말로 복습 끝(F16.8), 남은 5분과 2회째 뒤 3회째(F16.20), 남은 1분(F16.13)이다.
 * 끊김으로 멈추거나 그 뒤 다시 시작할 때는 연결이 없어 보내지 않는다.
 */
fun cues(before: SpeakingState, after: SpeakingState): List<Cue> = buildList {
    if (after.finished) return@buildList
    val paused = before.status != SpeakingStatus.Paused && after.status == SpeakingStatus.Paused
    // 말로 복습이 남은 5분을 넘겨 곧바로 마무리가 돼도 대화는 시작하게 한다.
    if (before.phase == SpeakingPhase.OralReview && after.phase != SpeakingPhase.OralReview) add(Cue.Talk)
    if (after.silenceCount > before.silenceCount && !paused) add(Cue.Rephrase)
    // 확인 질문 뒤 대답 없음은 횟수를 올리지 않고 한 번 다시 묻는다(F16.21). 확인 질문을 끄는 일은 사용자 말과 대답 없음뿐이고 사용자 말은 내가 말한 시간을 늘린다.
    if (before.clarifying && !after.clarifying && after.userTalk == before.userTalk) add(Cue.Rephrase)
    if (paused && !after.disconnected) add(Cue.Paused)
    if (before.status == SpeakingStatus.Paused && after.status != SpeakingStatus.Paused && !before.disconnected) add(Cue.Resumed)
    // 주제를 막 바꿔 첫 질문이 없으면 다시 물을 질문이 없다. 빈 질문을 보내지 않는다.
    if (before.phase != SpeakingPhase.Wrap && after.phase == SpeakingPhase.Wrap && after.firstQuestion != null) add(Cue.Wrap)
    if (!before.againSent && after.againSent) add(Cue.Again)
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
