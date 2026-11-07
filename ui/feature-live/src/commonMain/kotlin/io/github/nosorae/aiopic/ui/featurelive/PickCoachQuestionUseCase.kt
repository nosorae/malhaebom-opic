package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject

/** 코치와 이야기할 질문. */
data class CoachQuestion(val id: String, val text: String)

/** 코치에 낼 질문을 고른다. 앱 층이 한 문항 연습과 같은 고르기(F4.1)로 넣는다. 낼 질문이 없으면 null 이다. */
fun interface CoachQuestionPicker {
    suspend fun pick(): CoachQuestion?
}

@Inject
class PickCoachQuestionUseCase(private val picker: CoachQuestionPicker) {
    suspend operator fun invoke(): CoachQuestion? = picker.pick()
}
