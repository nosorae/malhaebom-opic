package io.github.nosorae.aiopic.ui.featurelive

import kotlinx.coroutines.flow.MutableStateFlow

/** 등급 다섯과 기본값 IH 를 메모리에 둔다. 실제 값은 시험 팩이 정한다. */
class FakeTargetGradeStore(grade: String = "IH") : TargetGradeStore {
    override val targetGrades = listOf("IM1", "IM2", "IM3", "IH", "AL")
    override val targetGrade = MutableStateFlow(grade)

    override suspend fun setTargetGrade(grade: String) {
        targetGrade.value = grade
    }
}
