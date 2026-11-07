package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Gemini 응답을 분석 포트의 결과로 바꾼다. 실패 이유는 분석, 받아쓰기, 스크립트가 같은 값을 쓴다. */
internal fun LlmResult.toAnalysis(): AnalysisResult<String> = when (this) {
    is LlmResult.Success -> AnalysisResult.Success(text)
    is LlmResult.Retryable -> AnalysisResult.Failed(if (httpStatus == null) AnalysisError.Offline else AnalysisError.Retryable)
    LlmResult.InvalidKey -> AnalysisResult.Failed(AnalysisError.InvalidKey)
    LlmResult.ModelNotFound -> AnalysisResult.Failed(AnalysisError.ModelUnavailable)
    is LlmResult.Rejected -> AnalysisResult.Failed(AnalysisError.Rejected)
}

private val json = Json { ignoreUnknownKeys = true }

/** 읽을 수 없는 JSON(빠진 필드, 출력 한도에 잘린 응답)이면 null 이다. */
internal fun <T> String.decodeOrNull(serializer: KSerializer<T>): T? = try {
    json.decodeFromString(serializer, this)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
