package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.port.Transcriber
import io.github.nosorae.aiopic.engine.answer.port.Transcript
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.AudioPart
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmRequest

/**
 * Gemini 로 녹음의 받아쓰기만 받는다(ADR 0019 의 받아쓰기 포트). 코치 리허설이 쓴다(F13.1). 답변 분석과 달리 채점은 하지 않는다.
 * 말한 말이 없으면 [UnscorableReason.Silent] 다.
 */
// ponytail: 단어 시각은 받지 않아 words 가 비어 있다. 두 단계 답변 분석 경로에 붙일 때 시각을 받는 스키마를 더한다.
class GeminiTranscriber(private val client: GeminiClient, private val apiKey: suspend () -> String?, private val model: ModelInfo) : Transcriber {
    override suspend fun transcribe(recording: Recording): AnalysisResult<Transcript> {
        val key = apiKey() ?: return AnalysisResult.Failed(AnalysisError.InvalidKey)
        return when (val result = client.generate(LlmRequest(apiKey = key, model = model.id, prompt = PROMPT, audio = listOf(AudioPart(recording.bytes, recording.mimeType)), thinkingLevel = "low")).toAnalysis()) {
            is AnalysisResult.Success -> result.value.trim().let { if (it.isEmpty()) AnalysisResult.Unscorable(UnscorableReason.Silent) else AnalysisResult.Success(Transcript(it, emptyList())) }
            is AnalysisResult.Failed -> result
            is AnalysisResult.Unscorable -> result
        }
    }

    private companion object {
        /** 고치지 않은 말 그대로를 받아야 피드백이 실제로 한 말을 짚는다. */
        const val PROMPT = "Transcribe the speech in this recording verbatim, in the language spoken. Do not correct grammar. Reply with the transcript only. If there is no speech, reply with an empty text."
    }
}
