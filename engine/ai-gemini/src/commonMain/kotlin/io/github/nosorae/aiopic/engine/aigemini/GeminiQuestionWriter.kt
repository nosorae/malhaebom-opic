package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmRequest
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmResult
import kotlinx.serialization.Serializable

/**
 * Gemini 로 한 회차의 질문을 만든다(F6.1). [prompt] 본문은 부르는 쪽이 주제, 수준, 재조정, 약점 유형, 문항 순서를 채운 글이다.
 * 키가 없거나 요청이 실패하거나 응답을 못 읽거나 유형 순서가 [order] 와 다르면 null 이고, 부르는 쪽은 내장 문항으로 고른다(F6.2).
 * 주제와 빈 질문 검사는 내장 문항으로 돌릴지 정하는 출제 쪽(`pickQuestions`)이 한다.
 */
// ponytail: 실패하면 다시 요청하지 않는다. 내장 문항이 있어서 시험은 그대로 진행되고, 무료 키의 하루 한도를 아낀다.
class GeminiQuestionWriter(private val client: GeminiClient, private val apiKey: suspend () -> String?) {
    /** 질문 id 는 [idPrefix] 뒤에 응답의 번호를 붙인다. 한 문항 연습이 id 로 이미 답한 질문을 가리므로 회차마다 달라야 한다. */
    suspend fun write(model: ModelInfo, prompt: Prompt, order: List<QuestionType>, idPrefix: String): List<Question>? {
        val key = apiKey() ?: return null
        val result = client.generate(LlmRequest(apiKey = key, model = model.id, prompt = prompt.text, responseSchema = prompt.responseSchema))
        val text = (result as? LlmResult.Success)?.text ?: return null
        val wire = text.decodeOrNull(QuestionsWire.serializer()) ?: return null
        return wire.questions.sortedBy { it.number }
            .map { Question(id = "$idPrefix${it.number}", text = it.text, type = QuestionType(it.type), topic = it.topic) }
            .takeIf { questions -> questions.map { it.type } == order }
    }
}

/** 출제 응답의 JSON 모양. 시험 팩의 출제 응답 클래스와 필드가 같다. 엔진은 시험 팩을 의존하지 못해서 따로 둔다. */
@Serializable
private class QuestionsWire(val questions: List<QuestionWire>)

@Serializable
private class QuestionWire(val number: Int, val type: String, val topic: String, val text: String)
