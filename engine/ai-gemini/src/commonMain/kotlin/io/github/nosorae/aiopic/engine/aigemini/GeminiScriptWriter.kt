package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.coach.ScriptResult
import io.github.nosorae.aiopic.engine.coach.ScriptWriter
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmRequest
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Gemini `generateContent` 한 번으로 스크립트를 만들고 다듬는다(F14.1, F14.4). 모델은 설정에서 고른 분석 모델이다([model]).
 * 프롬프트 본문은 시험 팩이 채워서 넘긴다([scriptPrompt], [polishPrompt]). 응답의 세 칸 가운데 하나라도 비었거나 JSON 을 못 읽으면 거절한다.
 */
// ponytail: 실패해도 다시 요청하지 않는다. 화면에 다시 시도가 있고 무료 키의 하루 한도를 아낀다.
class GeminiScriptWriter(
    private val client: GeminiClient,
    private val apiKey: suspend () -> String?,
    private val model: suspend () -> ModelInfo,
    private val scriptPrompt: suspend (CoachState, String) -> Prompt,
    private val polishPrompt: suspend (ScriptParts, List<Expression>, String, String) -> Prompt,
) : ScriptWriter {
    override suspend fun write(session: CoachState, question: String): ScriptResult<CoachScript> {
        val model = model()
        val prompt = scriptPrompt(session, question)
        return when (val result = generate(model, prompt)) {
            is ScriptResult.Failed -> result

            is ScriptResult.Success -> ScriptResult.Success(
                CoachScript(
                    sessionId = session.id,
                    original = result.value.parts,
                    // 모델은 표현 카드의 영어 글을 그대로 돌려준다. 카드에 없는 글은 버린다.
                    expressions = session.expressions.filter { it.english in result.value.usedExpressions },
                    edited = null,
                    editedAt = null,
                    promptVersion = prompt.version,
                    model = model.id,
                ),
            )
        }
    }

    override suspend fun polish(edited: ScriptParts, expressions: List<Expression>, question: String, targetGrade: String): ScriptResult<ScriptParts> = when (val result = generate(model(), polishPrompt(edited, expressions, question, targetGrade))) {
        is ScriptResult.Failed -> result
        is ScriptResult.Success -> ScriptResult.Success(result.value.parts)
    }

    private suspend fun generate(model: ModelInfo, prompt: Prompt): ScriptResult<ScriptWire> {
        val key = apiKey() ?: return ScriptResult.Failed(AnalysisError.InvalidKey)
        val text = when (val result = client.generate(LlmRequest(apiKey = key, model = model.id, prompt = prompt.text, responseSchema = prompt.responseSchema))) {
            is LlmResult.Success -> result.text
            is LlmResult.Retryable -> return ScriptResult.Failed(if (result.httpStatus == null) AnalysisError.Offline else AnalysisError.Retryable)
            LlmResult.InvalidKey -> return ScriptResult.Failed(AnalysisError.InvalidKey)
            LlmResult.ModelNotFound -> return ScriptResult.Failed(AnalysisError.ModelUnavailable)
            is LlmResult.Rejected -> return ScriptResult.Failed(AnalysisError.Rejected)
        }
        val wire = try {
            json.decodeFromString(ScriptWire.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return wire?.takeIf { listOf(it.intro, it.body, it.closing).none(String::isBlank) }?.let { ScriptResult.Success(it) } ?: ScriptResult.Failed(AnalysisError.Rejected)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** 스크립트 응답의 JSON 모양. 시험 팩의 스크립트 응답 클래스와 필드가 같다. 엔진은 시험 팩을 의존하지 못해서 따로 둔다. */
@Serializable
private class ScriptWire(val intro: String, val body: String, val closing: String, val usedExpressions: List<String>) {
    val parts: ScriptParts get() = ScriptParts(intro.trim(), body.trim(), closing.trim())
}
