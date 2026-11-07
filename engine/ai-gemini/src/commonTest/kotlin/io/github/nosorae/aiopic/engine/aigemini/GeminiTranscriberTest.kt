package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.port.Transcript
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 리허설 받아쓰기(F13.1). 응답 본문은 generateContent 응답 모양만 맞춘 손으로 쓴 글이다. 실제 응답을 저장해 둔 것이 아니다. */
class GeminiTranscriberTest {
    private val recording = Recording(ByteArray(4), "audio/mp4", emptyList())

    private fun transcriber(text: String, status: HttpStatusCode = HttpStatusCode.OK, key: String? = "test-key") = GeminiTranscriber(
        client = GeminiClient(HttpClient(MockEngine { respond("""{"candidates":[{"content":{"parts":[{"text":"$text"}],"role":"model"}}]}""", status) })),
        apiKey = { key },
        model = GeminiModels.transcriptModel,
    )

    @Test
    fun `F13_1 받은 글을 앞뒤 공백 없이 받아쓰기로 돌려준다`() = runTest {
        assertEquals(AnalysisResult.Success(Transcript("I went to Busan.", emptyList())), transcriber(" I went to Busan.\\n").transcribe(recording))
    }

    @Test
    fun `F13_1 받은 글이 비었으면 무음으로 평가할 수 없다`() = runTest {
        assertEquals(AnalysisResult.Unscorable(UnscorableReason.Silent), transcriber(" ").transcribe(recording))
    }

    @Test
    fun `키가 없으면 보내지 않고 키 오류다`() = runTest {
        assertEquals(AnalysisResult.Failed(AnalysisError.InvalidKey), transcriber("x", key = null).transcribe(recording))
    }

    @Test
    fun `서버 오류면 다시 시도할 수 있는 실패다`() = runTest {
        assertEquals(AnalysisResult.Failed(AnalysisError.Retryable), transcriber("x", HttpStatusCode.ServiceUnavailable).transcribe(recording))
    }
}
