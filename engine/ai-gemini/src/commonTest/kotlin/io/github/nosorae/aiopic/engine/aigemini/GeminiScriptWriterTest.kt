package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.coach.ScriptResult
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** 스크립트 만들기와 다듬기(F14.1, F14.4). 응답 본문은 스크립트 스키마 모양으로 손으로 쓴 것이고 가짜 클라이언트로만 돈다. */
class GeminiScriptWriterTest {
    private val blast = Expression("진짜 좋았어", "It was an absolute blast", "IH")
    private val session = CoachState("c1", "q1", "q", "IH", Instant.fromEpochSeconds(0), expressions = listOf(blast, Expression("배고팠어", "I was starving", "IM3")))
    private val prompts = mutableListOf<String>()
    private var requests = 0

    private fun writer(body: String, status: HttpStatusCode = HttpStatusCode.OK, key: String? = "test-key") = GeminiScriptWriter(
        client = GeminiClient(
            HttpClient(
                MockEngine {
                    requests++
                    respond(body, status)
                },
            ),
        ),
        apiKey = { key },
        model = { GeminiModels.defaultModel },
        scriptPrompt = { state, question -> Prompt("script-v1", "write ${state.id} $question", "{}").also { prompts += it.text } },
        polishPrompt = { edited, _, question, grade -> Prompt("polish-v1", "polish ${edited.intro} $question $grade", "{}").also { prompts += it.text } },
    )

    private fun envelope(intro: String, body: String = "We swam all day.", closing: String = "I want to go back.", used: List<String> = listOf(blast.english)) = buildJsonObject {
        val text = buildJsonObject {
            put("intro", intro)
            put("body", body)
            put("closing", closing)
            putJsonArray("usedExpressions") { used.forEach { add(JsonPrimitive(it)) } }
        }.toString()
        putJsonArray("candidates") { addJsonObject { putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } } } }
    }.toString()

    @Test
    fun `F14_1 세 칸과 쓴 표현을 받아 원본으로 둔다`() = runTest {
        val result = writer(envelope("I went to Busan.")).write(session, "Tell me about a trip.")

        assertEquals(
            ScriptResult.Success(CoachScript("c1", ScriptParts("I went to Busan.", "We swam all day.", "I want to go back."), listOf(blast), edited = null, editedAt = null, promptVersion = "script-v1", model = GeminiModels.defaultModel.id)),
            result,
        )
        assertEquals(listOf("write c1 Tell me about a trip."), prompts)
    }

    @Test
    fun `F14_1 빈 칸이 있는 응답은 거절한다`() = runTest {
        assertEquals(ScriptResult.Failed(AnalysisError.Rejected), writer(envelope(" ")).write(session, "q"))
    }

    @Test
    fun `F14_1 읽을 수 없는 응답은 거절한다`() = runTest {
        assertEquals(ScriptResult.Failed(AnalysisError.Rejected), writer(GeminiFixtures.ANSWER).write(session, "q"))
    }

    @Test
    fun `F14_1 요청이 실패하면 그 이유를 돌려준다`() = runTest {
        assertEquals(ScriptResult.Failed(AnalysisError.Retryable), writer("{}", HttpStatusCode.TooManyRequests).write(session, "q"))
    }

    @Test
    fun `F14_1 키가 없으면 보내지 않고 키 오류다`() = runTest {
        val result = writer(envelope("x"), key = null).write(session, "q")

        assertEquals(ScriptResult.Failed(AnalysisError.InvalidKey) to 0, result to requests)
    }

    @Test
    fun `F14_4 고친 판을 다듬은 세 칸을 돌려준다`() = runTest {
        val result = writer(envelope("I visited Busan.")).polish(ScriptParts("I went to Busan!", "b", "c"), listOf(blast), "q", "AL")

        assertEquals(ScriptResult.Success(ScriptParts("I visited Busan.", "We swam all day.", "I want to go back.")), result)
        assertEquals(listOf("polish I went to Busan! q AL"), prompts)
    }

    @Test
    fun `F14_4 다듬기도 빈 칸이면 거절한다`() = runTest {
        assertEquals(ScriptResult.Failed(AnalysisError.Rejected), writer(envelope("a", closing = "")).polish(ScriptParts("a", "b", "c"), emptyList(), "q", "AL"))
    }
}
