package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.SavedResponses
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.speaking.CardBatch
import io.github.nosorae.aiopic.engine.speaking.CardDraft
import io.github.nosorae.aiopic.engine.speaking.CardWriter
import io.github.nosorae.aiopic.engine.speaking.Line
import io.github.nosorae.aiopic.engine.speaking.capCards
import io.github.nosorae.aiopic.engine.speaking.checkCards
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 연습 정리의 카드 만들기(F17.1~F17.3). 대화 전문을 Gemini `generateContent` 한 번에 보내 카드 네 종류를 받는다. 모델은 [model] 이다.
 * 받은 카드는 엔진의 검사([checkCards])와 상한([capCards])을 거친다. 응답을 읽을 수 없거나 모델이 낸 카드가 하나도 검사를 통과하지 못하면
 * 한 번 다시 요청한다(F17.2). 분석 작업이 [SavedResponses] 를 문맥에 넣어 주면 받은 원문을 남기고, 남은 원문이 있으면 보내지 않고 다시 해석한다.
 */
class GeminiCardWriter(
    private val client: GeminiClient,
    private val apiKey: suspend () -> String?,
    private val model: suspend () -> ModelInfo,
) : CardWriter {
    override suspend fun write(transcript: List<Line>, existing: List<ExpressionCard>): AnalysisResult<CardBatch> {
        val saved = currentCoroutineContext()[SavedResponses]
        val request = LlmRequest(apiKey = apiKey() ?: return AnalysisResult.Failed(AnalysisError.InvalidKey), model = model().id, prompt = prompt(transcript), responseSchema = SCHEMA)
        var readable = false
        repeat(2) {
            val text = saved?.take(STAGE) ?: when (val result = client.generate(request).toAnalysis()) {
                // 받자마자 해석하기 전에 남긴다. 해석하다 앱이 죽어도 토큰을 쓴 응답은 남는다.
                is AnalysisResult.Success -> result.value.also { saved?.save(STAGE, it) }

                is AnalysisResult.Failed -> return result

                is AnalysisResult.Unscorable -> return result
            }
            val wire = text.decodeOrNull(CardsWire.serializer()) ?: return@repeat
            readable = true
            val passed = checkCards(wire.cards.mapNotNull(CardWire::draft), transcript, existing)
            // 모델이 카드가 없다고 답한 연습은 다시 물어도 같다. 지어낸 카드가 걸러진 때만 다시 요청한다.
            if (passed.isNotEmpty() || wire.cards.isEmpty()) return AnalysisResult.Success(capCards(passed))
        }
        return if (readable) AnalysisResult.Success(CardBatch(emptyList(), skippedErrors = 0)) else AnalysisResult.Failed(AnalysisError.Rejected)
    }

    private fun prompt(transcript: List<Line>) = INSTRUCTION + transcript.joinToString("\n") { "${it.speaker.name.lowercase()}: ${it.text.trim()}" }

    private companion object {
        /** [SavedResponses] 의 단계 이름. */
        const val STAGE = "cards"

        /** 카드 만들기 지시. 고칠 표현은 틀린 곳만 최소로 고치게 한다(F17.1, ADR 0041). */
        val INSTRUCTION = """
            You turn one English speaking practice of a Korean learner into review cards. Lines starting with "user:" are the learner and "coach:" is the AI coach.

            Make cards of four kinds:
            - Correction: a learner sentence with an error. Make one card per error, so a sentence with two errors gives two cards. Fix only the wrong part and keep every other word exactly as the learner said it. errorType is one of ${ErrorType.entries.joinToString { it.name }}.
            - UnknownExpression: the learner mixed in Korean or asked how to say something. said is the learner sentence exactly as transcribed, Korean included. better is a short English expression for the Korean part.
            - Formulaic: a chunk worth reusing whole, such as "The thing is,", "What I ended up doing was" or "mitigate the risk". said is the learner sentence where the chunk would have fit.
            - MoreNatural: a correct learner sentence that a fluent speaker would say differently. Use it only when the change is clearly more natural.

            Rules:
            - said is copied character for character from one user line. Never quote coach lines.
            - Do not correct a sentence that is already correct. Mixing in Korean is not an error, so it never becomes a Correction card.
            - errorType is only on Correction cards.
            - meaning is the Korean meaning of better. context is one short English line about the situation.
            - repeats is how many times the same errorType appeared in the whole practice. blocksMeaning is true when the error made the sentence hard to understand.
            - If nothing is worth a card, return an empty list.

            Transcript:

        """.trimIndent() + "\n"

        /** [CardsWire] 의 구조화 출력 스키마. 종류와 틀린 유형 값은 엔진 enum 이름에서 만든다. */
        val SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("cards") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("kind") { enum(CardKind.entries.map { it.name }) }
                            listOf("said", "better", "meaning", "context").forEach { putJsonObject(it) { put("type", "string") } }
                            putJsonObject("errorType") { enum(ErrorType.entries.map { it.name }) }
                            putJsonObject("repeats") { put("type", "integer") }
                            putJsonObject("blocksMeaning") { put("type", "boolean") }
                        }
                        putJsonArray("required") { listOf("kind", "said", "better", "meaning", "context", "repeats", "blocksMeaning").forEach(::add) }
                    }
                }
            }
            putJsonArray("required") { add("cards") }
        }.toString()

        private fun kotlinx.serialization.json.JsonObjectBuilder.enum(values: List<String>) {
            put("type", "string")
            putJsonArray("enum") { values.forEach(::add) }
        }
    }
}

/** 카드 응답의 JSON 모양. 종류와 틀린 유형은 글로 받아 모르는 값이면 그 카드만 버린다. */
@Serializable
private class CardsWire(val cards: List<CardWire>)

@Serializable
private class CardWire(
    val kind: String,
    val said: String,
    val better: String,
    val meaning: String,
    val errorType: String? = null,
    val context: String,
    val repeats: Int = 1,
    val blocksMeaning: Boolean = false,
) {
    fun draft(): CardDraft? = CardDraft(
        kind = CardKind.entries.firstOrNull { it.name == kind } ?: return null,
        said = said.trim(),
        better = better.trim(),
        meaning = meaning.trim(),
        errorType = ErrorType.entries.firstOrNull { it.name == errorType },
        context = context.trim(),
        repeats = repeats,
        blocksMeaning = blocksMeaning,
    )
}
