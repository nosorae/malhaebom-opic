package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.coach.CoachPhase
import io.github.nosorae.aiopic.engine.coach.CoachProfile
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 질문 하나와 목표 등급으로 코치 프로필을 만든다(F11). 지시는 `files/prompts/coach.md`, 도구 선언은 `coach-tools.json` 이다.
 * 버전은 coach.md 첫 줄의 promptVersion 이다. tools/eval 이 같은 두 파일을 읽어 실제 Live 응답을 잰다.
 */
suspend fun coachProfile(question: String, targetGrade: String): CoachProfile = coachProfile(
    PromptFile.parse(Res.readBytes("files/prompts/coach.md").decodeToString()),
    Res.readBytes("files/prompts/coach-tools.json").decodeToString(),
    question,
    targetGrade,
)

/**
 * 지시 파일은 `# common` 절과 단계 이름 절(`# explore` 등)로 나뉜다. 단계 지시는 공통 절 뒤에 그 단계 절을 붙인 것이다.
 * 절이 없는 단계(리허설, 스크립트)는 연결을 열지 않아 지시도 없다. 메모는 엔진의 instruction() 이 붙이므로 여기서 넣지 않는다.
 */
internal fun coachProfile(file: PromptFile, tools: String, question: String, targetGrade: String): CoachProfile {
    require(targetGrade in OpicEn.targetGrades) { "목표 등급이 아니다: $targetGrade" }
    val sections = file.fill(mapOf("question" to question, "grade" to targetGrade))
        .split(Regex("^# ", RegexOption.MULTILINE))
        .filter { it.isNotBlank() }
        .associate { it.substringBefore('\n').trim() to it.substringAfter('\n').trim() }
    val common = sections.getValue("common")
    return CoachProfile(
        instructions = CoachPhase.entries.mapNotNull { phase -> sections[phase.name.lowercase()]?.let { phase to "$common\n\n$it" } }.toMap(),
        tools = Json.parseToJsonElement(tools).jsonArray.map { it.jsonObject },
    )
}

/** 세션이 끝난 뒤 스크립트를 만드는 프롬프트(F14.1). [file] 은 `OpicPrompt.Script.load()` 다. */
fun scriptPrompt(file: PromptFile, question: String, session: CoachState): Prompt = prompt(
    file,
    OpicPrompt.Script,
    question,
    session.targetGrade,
    "transcript" to Json.encodeToString(session.lines),
    "materials" to Json.encodeToString(session.materials),
    "expressions" to Json.encodeToString(session.expressions),
    "rehearsals" to Json.encodeToString(session.rehearsals),
)

/** 사용자가 고친 스크립트를 [targetGrade] 길이에 맞춰 다듬는 프롬프트(F14.4). [file] 은 `OpicPrompt.ScriptPolish.load()` 다. */
fun polishPrompt(file: PromptFile, question: String, edited: ScriptParts, expressions: List<Expression>, targetGrade: String): Prompt = prompt(
    file,
    OpicPrompt.ScriptPolish,
    question,
    targetGrade,
    "script" to Json.encodeToString(edited),
    "expressions" to Json.encodeToString(expressions),
)

private fun prompt(file: PromptFile, kind: OpicPrompt, question: String, grade: String, vararg values: Pair<String, String>): Prompt {
    val length = OpicEn.scriptLengths.getValue(grade)
    val time = if (length.shortest == length.longest) "about ${length.shortest}" else "${length.shortest} to ${length.longest}"
    val text = file.fill(
        mapOf("question" to question, "grade" to grade, "length" to "$time spoken, ${length.sentences.first} to ${length.sentences.last} sentences") + values,
    )
    return Prompt(file.version, text, kind.responseSchema.toString())
}
