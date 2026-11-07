package io.github.nosorae.aiopic.ui.featurepractice

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.answer.weakness.weaknessScores
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType

/**
 * 한 문항 연습에 낼 질문 하나를 고른다(F4.1). 약점 점수가 가장 낮은 유형에서 내고, 그 유형 문항이 풀에 없으면 다음 약한 유형으로 간다.
 * 분석이 끝난 답변이 없으면 풀 전체에서 고른다. 같은 유형 안에서는 아직 답하지 않은 질문을 먼저 낸다. 풀이 비었으면 null 이다.
 * 왜 이 질문인지 화면이 보이게 고른 유형의 이름과 점수를 함께 돌려준다. 유형 이름([typeNames])은 시험 팩이 정해서 앱 층이 넣는다.
 */
// ponytail: 늘 가장 약한 유형만 낸다. 한 유형만 계속 나와 지루하다는 말이 나오면 점수에 반비례한 가중치로 고른다.
@Inject
class PickPracticeQuestionUseCase(private val repository: PracticeRepository, private val typeNames: Map<QuestionType, String>) {
    /** [questionId] 를 주면 그 질문을 낸다. 스크립트로 연습할 때다(F14.5). 풀에 없으면 약한 유형에서 고른다. */
    suspend operator fun invoke(questionId: String? = null): PracticePick? {
        val pool = repository.pool()
        pool.firstOrNull { it.id == questionId }?.let { return PracticePick(it, weakness = null) }
        val answers = repository.answers()
        val picked = weaknessScores(answers, repository.flaggedCorrections())
            .firstNotNullOfOrNull { weak -> pool.filter { it.type == weak.type }.ifEmpty { null }?.let { weak to it } }
        val candidates = picked?.second ?: pool
        val answered = answers.map { it.question.id }.toSet()
        val question = candidates.filter { it.id !in answered }.ifEmpty { candidates }.randomOrNull() ?: return null
        val weakness = picked?.first?.let { PracticeWeakness(typeNames[it.type] ?: it.type.id, it.score) }
        return PracticePick(question, weakness)
    }
}

/** 낼 질문과 그 질문을 고른 약한 유형. 분석한 답변이 없어 풀 전체에서 골랐으면 [weakness] 가 null 이다. */
data class PracticePick(val question: Question, val weakness: PracticeWeakness?)

/** 사용자에게 보일 유형 이름과 그 유형의 약점 점수(0~100). */
data class PracticeWeakness(val typeName: String, val score: Int)
