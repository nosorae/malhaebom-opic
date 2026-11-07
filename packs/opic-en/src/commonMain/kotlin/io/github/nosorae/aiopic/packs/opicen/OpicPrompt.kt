package io.github.nosorae.aiopic.packs.opicen

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject

/**
 * 모델에 보내는 프롬프트 셋과 응답 스키마. 본문은 `composeResources/files/prompts/` 의 파일이고
 * 파일마다 promptVersion 이 있어 답변 결과에 그대로 남긴다(F5.7). 문장을 고치면 그 파일의 버전을 올린다.
 */
enum class OpicPrompt(private val path: String, response: SerialDescriptor) {
    /** 답변 하나의 피드백(F5.2, F5.5). */
    Answer("files/prompts/answer.md", AnswerResponse.serializer().descriptor),

    /** 15번이 끝난 뒤 회차 등급(F5.3). */
    SessionGrade("files/prompts/session-grade.md", SessionGradeResponse.serializer().descriptor),

    /** 출제(F6.1). */
    Questions("files/prompts/questions.md", QuestionsResponse.serializer().descriptor),

    /** 코치 세션으로 스크립트 만들기(F14.1). 채우기는 [scriptPrompt]. */
    Script("files/prompts/script.md", ScriptResponse.serializer().descriptor),

    /** 사용자가 고친 스크립트를 목표 등급 길이에 맞춰 다듬기(F14.4). 채우기는 [polishPrompt]. */
    ScriptPolish("files/prompts/script-polish.md", ScriptResponse.serializer().descriptor),
    ;

    val responseSchema: JsonObject = jsonSchema(response)

    suspend fun load(): PromptFile = PromptFile.parse(Res.readBytes(path).decodeToString())
}
