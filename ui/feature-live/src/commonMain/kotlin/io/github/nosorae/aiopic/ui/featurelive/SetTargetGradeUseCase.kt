package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject

@Inject
class SetTargetGradeUseCase(private val store: TargetGradeStore) {
    suspend operator fun invoke(grade: String) = store.setTargetGrade(grade)
}
