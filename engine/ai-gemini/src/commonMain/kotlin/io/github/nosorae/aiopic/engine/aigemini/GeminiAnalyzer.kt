package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.check.checkFeedback
import io.github.nosorae.aiopic.engine.answer.check.checkSessionGrade
import io.github.nosorae.aiopic.engine.answer.check.requestChecked
import io.github.nosorae.aiopic.engine.answer.model.ContentFix
import io.github.nosorae.aiopic.engine.answer.model.Correction
import io.github.nosorae.aiopic.engine.answer.model.Feedback
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.Pause
import io.github.nosorae.aiopic.engine.answer.model.Pronunciation
import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.model.Upgrade
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.port.AudioAnswerAnalyzer
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.port.SavedResponses
import io.github.nosorae.aiopic.engine.answer.port.SessionGrader
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.GradeScale
import io.github.nosorae.aiopic.engine.session.model.SessionGrade
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.AudioPart
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmRequest
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/**
 * Gemini 한 번 호출 경로(ADR 0019). 녹음을 직접 들려주고 답변 피드백과 회차 등급을 받는다.
 * 받은 값은 출력 검사(F5.4)를 거치고, 통과 못하면 한 번 다시 요청한다.
 * [prompt] 본문은 부르는 쪽이 문항과 이어 녹음 위치를 채운 글이다.
 * 분석 작업이 [SavedResponses] 를 문맥에 넣어 주면 받은 원문을 해석하기 전에 남기고, 남은 원문이 있으면 보내지 않고 그것을 해석한다(#205).
 */
class GeminiAnalyzer(
    private val client: GeminiClient,
    /** 저장된 Gemini 키. 없으면 요청하지 않고 [AnalysisError.InvalidKey] 로 끝낸다. */
    private val apiKey: suspend () -> String?,
) : AudioAnswerAnalyzer,
    SessionGrader {
    override suspend fun analyze(model: ModelInfo, prompt: Prompt, question: Question, recording: Recording): AnalysisResult<Feedback> = requestChecked(::checkFeedback) {
        when (val result = generate(ANSWER, model, prompt, listOf(recording), AnswerWire.serializer())) {
            is AnalysisResult.Success -> result.value.toResult()
            is AnalysisResult.Unscorable -> result
            is AnalysisResult.Failed -> result
        }
    }

    override suspend fun grade(model: ModelInfo, prompt: Prompt, scale: GradeScale, recordings: List<Recording>): AnalysisResult<SessionGrade> = requestChecked({ checkSessionGrade(it, scale, recordings.size) }) {
        generate(GRADE, model, prompt, recordings, SessionGrade.serializer())
    }

    private suspend fun <T> generate(stage: String, model: ModelInfo, prompt: Prompt, recordings: List<Recording>, serializer: KSerializer<T>): AnalysisResult<T> {
        val saved = currentCoroutineContext()[SavedResponses]
        // 읽을 수 없는 JSON(출력 한도에 잘린 응답 등)은 출력 검사(F5.4)처럼 한 번 다시 요청하고, 두 번째도 못 읽으면 거절한다.
        // 남은 원문은 첫 번째로 센다. 남은 원문을 못 읽으면 한 번만 새로 받는다.
        repeat(2) {
            val text = saved?.take(stage) ?: when (val result = send(model, prompt, recordings, THINKING[stage]).toAnalysis()) {
                // 받자마자 해석하기 전에 남긴다. 해석하다 앱이 죽어도 토큰을 쓴 응답은 남는다.
                is AnalysisResult.Success -> result.value.also { saved?.save(stage, it) }

                is AnalysisResult.Failed -> return result

                is AnalysisResult.Unscorable -> return result
            }
            text.decodeOrNull(serializer)?.let { return AnalysisResult.Success(it) }
        }
        return AnalysisResult.Failed(AnalysisError.Rejected)
    }

    private suspend fun send(model: ModelInfo, prompt: Prompt, recordings: List<Recording>, thinkingLevel: String?): LlmResult {
        val key = apiKey() ?: return LlmResult.InvalidKey
        return client.generate(
            LlmRequest(
                apiKey = key,
                model = model.id,
                prompt = prompt.text,
                audio = recordings.map { AudioPart(it.bytes, it.mimeType) },
                responseSchema = prompt.responseSchema,
                thinkingLevel = thinkingLevel,
            ),
        )
    }

    private companion object {
        /** [SavedResponses] 의 단계 이름. */
        const val ANSWER = "answer"
        const val GRADE = "grade"

        /**
         * 단계별 생각 수준(#216). 답변 분석은 낮춘다. 같은 녹음 네 개를 기본과 낮춘 설정으로 보내 보니 평균 10.2초가 5.6초가 됐고,
         * 넣은 문법 오류 다섯 개를 둘 다 모두 잡았다. 롤플레이 답의 점수는 낮춘 쪽이 7~15점 높게 나왔다.
         * 회차 등급은 회차에 한 번이고 앱이 내는 결론이라 기본값으로 둔다.
         */
        val THINKING = mapOf(ANSWER to "low")
    }
}

/**
 * 답변 분석 응답의 JSON 모양. 시험 팩의 응답 클래스(스키마 기준)와 필드가 같아야 한다. 엔진은 시험 팩을 의존하지 못해서 따로 둔다.
 * 시간은 모델이 쓰기 쉬운 초 단위 숫자로 받는다.
 */
@Serializable
private class AnswerWire(
    val transcript: String,
    val corrections: List<Correction>,
    val upgrades: List<Upgrade>,
    val tenseConsistent: Boolean,
    val sentenceCount: Int,
    val wordsPerMinute: Double,
    val pauses: List<PauseWire>,
    val roleplayQuestionCount: Int? = null,
    val pronunciation: String,
    val score: Int,
    val unscorableReason: UnscorableReason? = null,
    // 프롬프트 v3(#217) 전에 저장한 응답 원문에는 없다.
    val contentFixes: List<ContentFix> = emptyList(),
    val finalScript: String? = null,
) {
    // 받아쓴 말이 없는데 점수가 오면 지어낸 값이다. 이유 코드를 빠뜨렸어도 무음으로 친다(#241).
    fun toResult(): AnalysisResult<Feedback> = (unscorableReason ?: UnscorableReason.Silent.takeIf { transcript.isBlank() })?.let { AnalysisResult.Unscorable(it) } ?: AnalysisResult.Success(
        Feedback(
            transcript = transcript,
            corrections = corrections,
            upgrades = upgrades,
            tenseConsistent = tenseConsistent,
            sentenceCount = sentenceCount,
            wordsPerMinute = wordsPerMinute,
            pauses = pauses.map { Pause(start = it.startSeconds.seconds, length = it.lengthSeconds.seconds) },
            roleplayQuestionCount = roleplayQuestionCount,
            pronunciation = Pronunciation.Evaluated(pronunciation),
            score = score,
            contentFixes = contentFixes,
            finalScript = finalScript,
        ),
    )
}

@Serializable
private class PauseWire(val startSeconds: Double, val lengthSeconds: Double)
