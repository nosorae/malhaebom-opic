package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.SavedResponses
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.speaking.CardBatch
import io.github.nosorae.aiopic.engine.speaking.CardDraft
import io.github.nosorae.aiopic.engine.speaking.Line
import io.github.nosorae.aiopic.engine.speaking.Speaker
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 연습 정리의 카드 만들기(F17.1, F17.2, F17.3). 응답 본문은 카드 스키마 모양으로 손으로 쓴 것이고 가짜 클라이언트로만 돈다. */
class GeminiCardWriterTest {
    private val transcript = listOf(
        Line(Speaker.Coach, "What did you do last weekend?"),
        Line(Speaker.User, "I go to the beach with my friend. 그때 파도가 너무 세서 we just sit there."),
        Line(Speaker.Coach, "Sounds fun."),
    )
    private val bodies = ArrayDeque<String>()
    private val prompts = mutableListOf<String>()

    private val writer = GeminiCardWriter(
        client = GeminiClient(
            HttpClient(
                MockEngine { request ->
                    prompts += request.body.toByteArray().decodeToString()
                    respond(bodies.removeFirstOrNull() ?: error("넣어 둔 응답이 없다"), HttpStatusCode.OK)
                },
            ),
        ),
        apiKey = { "test-key" },
        model = { GeminiModels.defaultModel },
    )

    private fun card(kind: String, said: String, better: String, errorType: String? = null, repeats: Int = 1, blocksMeaning: Boolean = false) = buildJsonObject {
        put("kind", kind)
        put("said", said)
        put("better", better)
        put("meaning", "뜻")
        errorType?.let { put("errorType", it) }
        put("context", "Talking about a weekend trip")
        put("repeats", repeats)
        put("blocksMeaning", blocksMeaning)
    }

    private fun envelope(vararg cards: JsonObject) = envelope(buildJsonObject { putJsonArray("cards") { cards.forEach { add(it) } } }.toString())

    private fun envelope(text: String) = buildJsonObject {
        putJsonArray("candidates") { addJsonObject { putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } } } }
    }.toString()

    @Test
    fun `F17_1 저장한 응답을 네 종류 카드로 읽는다`() = runTest {
        bodies += envelope(
            card("Correction", "I go to the beach", "I went to the beach", "Tense", repeats = 2),
            card("UnknownExpression", "그때 파도가 너무 세서", "the waves were too rough"),
            card("Formulaic", "we just sit there", "we ended up just sitting there"),
            card("MoreNatural", "I go to the beach with my friend.", "I hit the beach with a friend."),
        )

        val result = writer.write(transcript, existing = emptyList())

        val cards = (result as AnalysisResult.Success).value.cards
        assertEquals(listOf(CardKind.Correction, CardKind.UnknownExpression, CardKind.Formulaic, CardKind.MoreNatural), cards.map { it.kind })
        assertEquals(CardDraft(CardKind.Correction, "I go to the beach", "I went to the beach", "뜻", ErrorType.Tense, "Talking about a weekend trip", repeats = 2), cards.first())
    }

    @Test
    fun `F17_1 요청에 대화 전문과 최소 수정 지시와 출력 스키마를 싣는다`() = runTest {
        bodies += envelope()

        writer.write(transcript, existing = emptyList())

        val body = prompts.single()
        assertTrue("user: I go to the beach with my friend." in body)
        assertTrue("Fix only the wrong part" in body)
        assertTrue("\"schema\"" in body && "\"blocksMeaning\"" in body)
    }

    @Test
    fun `F17_2 내가 한 말이 사용자 말에 없거나 같은 카드가 이미 있으면 그 카드만 버린다`() = runTest {
        bodies += envelope(
            card("Correction", "I go to the beach", "I went to the beach", "Tense"),
            card("Correction", "What did you do", "What have you done", "Tense"),
            card("Correction", "I goes to school", "I go to school", "Tense"),
            card("Formulaic", "we just sit there", "We ended up just sitting there."),
        )
        val existing = ExpressionCard(kind = CardKind.Formulaic, said = "s", better = "we ended up just sitting there", meaning = "m", errorType = null, context = "c", createdAt = Instant.fromEpochSeconds(0), dueAt = Instant.fromEpochSeconds(0))

        val result = writer.write(transcript, existing = listOf(existing))

        assertEquals(listOf("I went to the beach"), (result as AnalysisResult.Success).value.cards.map { it.better })
    }

    @Test
    fun `F17_1 틀린 유형은 고칠 표현에만 두고 유형 없는 고칠 표현은 버린다`() = runTest {
        bodies += envelope(
            card("Correction", "I go to the beach", "I went to the beach"),
            card("UnknownExpression", "그때 파도가 너무 세서", "the waves were too rough", "WordChoice"),
        )

        val cards = (writer.write(transcript, existing = emptyList()) as AnalysisResult.Success).value.cards

        assertEquals(listOf(CardKind.UnknownExpression to null), cards.map { it.kind to it.errorType })
    }

    @Test
    fun `F17_3 고칠 표현은 반복된 유형과 뜻을 막은 것부터 8장 더 자연스럽게는 2장으로 자르고 남은 고칠 표현을 넘긴 오류로 센다`() = runTest {
        val corrections = (1..10).map { card("Correction", "I go to the beach", "fix $it", "Article", repeats = if (it == 10) 3 else 1, blocksMeaning = it == 9) }
        val natural = (1..3).map { card("MoreNatural", "we just sit there", "natural $it") }
        bodies += envelope(*(corrections + natural).toTypedArray())

        val batch = (writer.write(transcript, existing = emptyList()) as AnalysisResult.Success).value

        val kept = batch.cards.filter { it.kind == CardKind.Correction }.map { it.better }
        assertEquals(8, kept.size)
        assertEquals(listOf("fix 10", "fix 9"), kept.take(2))
        assertEquals(listOf("natural 1", "natural 2"), batch.cards.filter { it.kind == CardKind.MoreNatural }.map { it.better })
        assertEquals(2, batch.skippedErrors)
    }

    @Test
    fun `F17_2 검사를 통과한 카드가 없으면 한 번 다시 요청한다`() = runTest {
        bodies += envelope(card("Correction", "made up sentence", "fixed", "Tense"))
        bodies += envelope(card("Correction", "I go to the beach", "I went to the beach", "Tense"))

        val result = writer.write(transcript, existing = emptyList())

        assertEquals(2, prompts.size)
        assertEquals(listOf("I went to the beach"), (result as AnalysisResult.Success).value.cards.map { it.better })
    }

    @Test
    fun `F17_2 다시 받은 응답도 읽을 수 없으면 거절해 작업이 다시 시도하게 한다`() = runTest {
        bodies += envelope("not json")
        bodies += envelope("{\"cards\": [")

        val result = writer.write(transcript, existing = emptyList())

        assertEquals(2, prompts.size)
        assertEquals(AnalysisResult.Failed(AnalysisError.Rejected), result)
    }

    @Test
    fun `F17_2 다시 받아도 통과한 카드가 없으면 카드 없이 끝낸다`() = runTest {
        repeat(2) { bodies += envelope(card("Correction", "made up sentence", "fixed", "Tense")) }

        val result = writer.write(transcript, existing = emptyList())

        assertEquals(2, prompts.size)
        assertEquals(AnalysisResult.Success(CardBatch(emptyList(), 0)), result)
    }

    @Test
    fun `F17_2 모델이 카드가 없다고 답하면 다시 요청하지 않는다`() = runTest {
        bodies += envelope()

        val result = writer.write(transcript, existing = emptyList())

        assertEquals(1, prompts.size)
        assertEquals(AnalysisResult.Success(CardBatch(emptyList(), 0)), result)
    }

    @Test
    fun `F17_4 저장해 둔 원문이 있으면 보내지 않고 다시 해석한다`() = runTest {
        val raw = buildJsonObject { putJsonArray("cards") { add(card("Correction", "I go to the beach", "I went to the beach", "Tense")) } }.toString()
        val saved = SavedResponses({ stage -> raw.takeIf { stage == "cards" } }, { _, _ -> })

        val result = withContext(saved) { writer.write(transcript, existing = emptyList()) }

        assertEquals(0, prompts.size)
        assertEquals(1, (result as AnalysisResult.Success).value.cards.size)
    }

    @Test
    fun `F17_4 요청이 실패하면 그 이유를 돌려준다`() = runTest {
        val failing = GeminiCardWriter(GeminiClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) })), { "test-key" }, { GeminiModels.defaultModel })

        assertEquals(AnalysisResult.Failed(AnalysisError.Retryable), failing.write(transcript, existing = emptyList()))
    }
}
