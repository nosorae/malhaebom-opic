package io.github.nosorae.aiopic.engine.coach

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

val TALK_WARNING = 25.minutes
val TALK_LIMIT = 30.minutes

/** 이어 붙이기 핸들은 끊긴 뒤 2시간 유효하다. 받은 시각부터 재서 조금 일찍 만료로 본다. */
val RESUME_LIMIT = 2.hours

const val MAX_REHEARSALS = 2

/** 피드백 단계에서 한 번 더 리허설할 수 있다(F13.3). */
val CoachState.canRehearseAgain: Boolean get() = phase == CoachPhase.Feedback && rehearsals.size < MAX_REHEARSALS

/** 다음 연결 지시에 넣는 최근 받아쓰기 줄 수. 앞 단계 끝 대화만 이으면 되고 길면 다시 읽는 입력이 는다. */
const val RECENT_LINES = 6

/** 100만 토큰당 USD. 연결은 음성으로만 답해서 글 출력 단가는 두지 않는다. */
val INPUT_USD_PER_MILLION = mapOf(Modality.Audio to 3.0, Modality.Text to 0.75)
val OUTPUT_USD_PER_MILLION = mapOf(Modality.Audio to 12.0)

/** 지금 상태와 일어난 일로 다음 상태를 낸다(ADR 0012 방식). 끝난 세션은 바뀌지 않는다. */
fun reduce(state: CoachState, event: CoachEvent): CoachState {
    if (state.status == CoachStatus.Finished) return state
    return when (event) {
        is CoachEvent.Heard -> state.copy(lines = state.lines.append(Line(state.phase, event.speaker, event.text)))

        is CoachEvent.ToolCalled -> if (toolError(event.name, event.args) != null) state else state.apply(event.name, event.args)

        is CoachEvent.Used -> state.copy(usage = state.usage + (state.phase to (state.usage[state.phase] ?: TokenUsage()) + event.usage))

        CoachEvent.NextPhase -> state.next()

        is CoachEvent.Rehearsed ->
            if (state.phase == CoachPhase.Rehearse && state.rehearsals.size < MAX_REHEARSALS && event.text.isNotBlank()) state.copy(rehearsals = state.rehearsals + event.text) else state

        CoachEvent.RehearseAgain -> if (state.canRehearseAgain) state.copy(phase = CoachPhase.Rehearse, resumeHandle = null, handleAt = null) else state

        is CoachEvent.MaterialEdited -> state.copy(materials = state.materials.filter { it.kind != event.kind } + listOfNotNull(Material(event.kind, event.text.trim()).takeIf { it.text.isNotEmpty() }))

        is CoachEvent.ExpressionEdited ->
            if (event.index in state.expressions.indices) state.copy(expressions = state.expressions.toMutableList().apply { set(event.index, event.expression) }) else state

        is CoachEvent.TimePassed -> (state.talkTime + event.duration).let { if (it >= TALK_LIMIT) state.copy(talkTime = it).finish() else state.copy(talkTime = it) }

        CoachEvent.Paused -> state.copy(status = CoachStatus.Paused)

        CoachEvent.Resumed -> state.copy(status = CoachStatus.Active, disconnected = null)

        // 먼저 받은 이유를 남긴다.
        is CoachEvent.Dropped -> if (state.disconnected != null) state else state.copy(disconnected = event.reason)

        is CoachEvent.HandleReceived -> state.copy(resumeHandle = event.handle, handleAt = event.at)
    }
}

/** 도구 인자가 틀렸으면 모델에게 돌려줄 에러 글, 맞으면 null. 틀린 호출은 상태를 바꾸지 않는다. */
fun toolError(name: String, args: JsonObject): String? = when (name) {
    CoachTools.ADVANCE_PHASE -> null

    CoachTools.SAVE_MATERIAL -> when {
        materialKind(args) == null -> "kind must be one of place, person, event, feeling"
        args.text("text").isNullOrBlank() -> "text is empty"
        else -> null
    }

    CoachTools.SAVE_EXPRESSION -> if (EXPRESSION_ARGS.any { args.text(it).isNullOrBlank() }) "user_said, english and grade are required" else null

    else -> "unknown tool $name"
}

/** 지금 단계의 시스템 지시. 앞에서 모은 메모와 최근 받아쓰기를 글로 붙여 음성 맥락 대신 넘긴다. */
fun instruction(profile: CoachProfile, state: CoachState): String {
    val notes = buildList {
        state.materials.forEach { add("${it.kind.name.lowercase()}: ${it.text}") }
        state.expressions.forEach { add("expression: ${it.userSaid} / ${it.english} (${it.grade})") }
        state.rehearsals.forEachIndexed { i, text -> add("rehearsal ${i + 1}: $text") }
        state.lines.takeLast(RECENT_LINES).forEach { add("${it.speaker.name.lowercase()}: ${it.text}") }
    }
    val base = profile.instructions[state.phase].orEmpty()
    return if (notes.isEmpty()) base else "$base\n\nNotes so far:\n${notes.joinToString("\n")}"
}

private val EXPRESSION_ARGS = listOf("user_said", "english", "grade")

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun materialKind(args: JsonObject) = MaterialKind.entries.firstOrNull { it.name.lowercase() == args.text("kind") }

private fun CoachState.apply(tool: String, args: JsonObject) = when (tool) {
    CoachTools.ADVANCE_PHASE -> next()
    CoachTools.SAVE_MATERIAL -> copy(materials = materials + Material(materialKind(args)!!, args.text("text")!!))
    CoachTools.SAVE_EXPRESSION -> copy(expressions = expressions + Expression(args.text("user_said")!!, args.text("english")!!, args.text("grade")!!))
    else -> this
}

/** 받아쓰기는 조각으로 온다. 같은 단계에서 같은 사람이 이어 말하면 한 줄로 잇는다. */
private fun List<Line>.append(line: Line): List<Line> {
    val last = lastOrNull()
    return if (last != null && last.phase == line.phase && last.speaker == line.speaker) dropLast(1) + last.copy(text = last.text + line.text) else this + line
}

/** 단계마다 새 연결을 열어서 앞 단계 핸들은 버린다. */
private fun CoachState.next() = if (phase >= CoachPhase.Feedback) finish() else copy(phase = CoachPhase.entries[phase.ordinal + 1], resumeHandle = null, handleAt = null)

private fun CoachState.finish() = copy(phase = CoachPhase.Script, status = CoachStatus.Finished, resumeHandle = null, handleAt = null)
