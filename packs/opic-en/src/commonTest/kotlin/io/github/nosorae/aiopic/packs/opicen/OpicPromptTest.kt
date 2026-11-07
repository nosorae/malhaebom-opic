package io.github.nosorae.aiopic.packs.opicen

import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpicPromptTest {
    private fun JsonObject.obj(vararg path: String): JsonObject = path.fold(this) { node, key -> node.getValue(key).jsonObject }

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `F5_2 답변 분석 응답 예시는 스키마 그대로 읽힌다`() {
        val response = Json.decodeFromString<AnswerResponse>(ANSWER_FIXTURE)

        assertEquals("a apartment" to 10, response.corrections.single().let { it.original to it.deducted })
    }

    @Test
    fun `F5_5 평가할 수 없는 답변은 이유 코드와 빈 값으로 읽힌다`() {
        val response = Json.decodeFromString<AnswerResponse>(
            """{"transcript":"","corrections":[],"upgrades":[],"tenseConsistent":false,"sentenceCount":0,"wordsPerMinute":0,"pauses":[],"pronunciation":"","score":0,"contentFixes":[],"finalScript":"","unscorableReason":"too_short"}""",
        )

        assertEquals(UnscorableReason.TooShort, response.unscorableReason)
    }

    @Test
    fun `F5_3 회차 등급 응답 예시는 스키마 그대로 읽힌다`() {
        val response = Json.decodeFromString<SessionGradeResponse>(SESSION_GRADE_FIXTURE)

        assertEquals(OpicGrade.IH to OpicGrade.AL, response.low to response.high)
    }

    @Test
    fun `F6_1 출제 응답 예시는 스키마 그대로 읽힌다`() {
        val response = Json.decodeFromString<QuestionsResponse>(QUESTIONS_FIXTURE)

        assertEquals(OpicQuestionType.SelfIntro, response.questions.first().type)
    }

    @Test
    fun `등급 체계에 없는 등급이 오면 읽지 않는다`() {
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<SessionGradeResponse>(SESSION_GRADE_FIXTURE.replace("\"AL\"", "\"S\"")) }
    }

    @Test
    fun `답변 분석 스키마는 응답의 필드를 모두 담고 null 이 될 수 있는 필드만 required 에서 뺀다`() {
        val schema = OpicPrompt.Answer.responseSchema
        val optional = setOf("roleplayQuestionCount", "unscorableReason")

        assertEquals(
            setOf("transcript", "corrections", "upgrades", "tenseConsistent", "sentenceCount", "wordsPerMinute", "pauses", "pronunciation", "score", "contentFixes", "finalScript") + optional,
            schema.obj("properties").keys,
        )
        assertEquals(schema.obj("properties").keys - optional, schema.strings("required"))
    }

    @Test
    fun `F8_2 답변 분석 스키마는 교정마다 깎은 점수를 정수로 꼭 받는다`() {
        val correction = OpicPrompt.Answer.responseSchema.obj("properties", "corrections", "items")

        assertEquals("integer", correction.obj("properties", "deducted").getValue("type").jsonPrimitive.content)
        assertEquals(setOf("original", "corrected", "deducted"), correction.strings("required"))
    }

    @Test
    fun `217 답변 분석 스키마는 내용 교정마다 원문과 어색한 점과 고친 문장을 꼭 받는다`() {
        val fix = OpicPrompt.Answer.responseSchema.obj("properties", "contentFixes", "items")

        assertEquals(setOf("original", "problem", "corrected"), fix.strings("required"))
    }

    @Test
    fun `217 답변 분석 응답 예시의 내용 교정과 최종 스크립트를 읽는다`() {
        val response = Json.decodeFromString<AnswerResponse>(ANSWER_FIXTURE)

        assertEquals("Last year I move to Seoul." to true, response.contentFixes.single().original to response.finalScript.startsWith("I live in"))
    }

    @Test
    fun `F5_5 답변 분석 스키마의 이유 코드는 고를 수 있는 값만 둔다`() {
        val reason = OpicPrompt.Answer.responseSchema.obj("properties", "unscorableReason")

        assertEquals("string" to setOf("silent", "wrong_language", "too_short"), reason.getValue("type").jsonPrimitive.content to reason.strings("enum"))
    }

    @Test
    fun `답변 분석 스키마의 목록 항목은 필드 타입까지 적는다`() {
        val pause = OpicPrompt.Answer.responseSchema.obj("properties", "pauses", "items")

        assertEquals("""{"type":"object","properties":{"startSeconds":{"type":"number"},"lengthSeconds":{"type":"number"}},"required":["startSeconds","lengthSeconds"]}""", pause.toString())
    }

    @Test
    fun `F5_3 회차 등급 스키마의 등급은 등급 체계의 값만 고를 수 있다`() {
        val low = OpicPrompt.SessionGrade.responseSchema.obj("properties", "low")

        assertEquals(OpicEn.gradeScale.grades.toSet(), low.strings("enum"))
    }

    @Test
    fun `F6_1 출제 스키마의 문항 유형은 팩의 유형만 고를 수 있다`() {
        val type = OpicPrompt.Questions.responseSchema.obj("properties", "questions", "items", "properties", "type")

        assertEquals(OpicQuestionType.entries.map { it.type.id }.toSet(), type.strings("enum"))
    }

    @Test
    fun `모든 스키마의 최상위가 object 이다`() {
        assertTrue(OpicPrompt.entries.all { it.responseSchema["type"]?.jsonPrimitive?.content == "object" && it.responseSchema["properties"] is JsonObject && it.responseSchema["required"] is JsonArray })
    }

    private companion object {
        const val ANSWER_FIXTURE = """
            {
              "transcript": "I live in a apartment. Last year I move to Seoul.",
              "corrections": [{"original": "a apartment", "corrected": "I live in an apartment.", "deducted": 10}],
              "upgrades": [{"original": "I live in an apartment.", "better": "I've been living in a cozy two-bedroom apartment."}],
              "tenseConsistent": false,
              "sentenceCount": 2,
              "wordsPerMinute": 112.5,
              "pauses": [{"startSeconds": 4.2, "lengthSeconds": 1.8}],
              "pronunciation": "Final consonants are often dropped.",
              "score": 55,
              "contentFixes": [{"original": "Last year I move to Seoul.", "problem": "집을 묘사하라는 질문에 이사 이야기로 넘어갔다.", "corrected": "I moved into this apartment last year when I came to Seoul."}],
              "finalScript": "I live in an apartment in Seoul. I moved into this place last year when I came here for work."
            }
        """

        const val SESSION_GRADE_FIXTURE = """
            {
              "checks": [{"id": "C1", "met": true}, {"id": "C9", "met": false}],
              "low": "IH",
              "high": "AL",
              "reasons": ["Keeps paragraph-length answers on most questions."],
              "brokeAt": [14]
            }
        """

        const val QUESTIONS_FIXTURE = """
            {
              "questions": [
                {"number": 1, "type": "self-intro", "topic": "self", "text": "Let's start the interview. Tell me a little about yourself."},
                {"number": 2, "type": "description", "topic": "home", "text": "Describe the home you live in."}
              ]
            }
        """
    }
}
