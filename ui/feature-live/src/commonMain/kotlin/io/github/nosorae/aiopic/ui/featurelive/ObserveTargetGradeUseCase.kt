package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 고를 수 있는 목표 등급과 지금 고른 등급. */
data class TargetGradeChoice(val grades: List<String>, val selected: String)

@Inject
class ObserveTargetGradeUseCase(private val store: TargetGradeStore) {
    operator fun invoke(): Flow<TargetGradeChoice> = store.targetGrade.map { TargetGradeChoice(store.targetGrades, it) }
}
