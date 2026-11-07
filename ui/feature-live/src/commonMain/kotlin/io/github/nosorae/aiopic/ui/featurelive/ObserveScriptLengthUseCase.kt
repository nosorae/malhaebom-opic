package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.ScriptLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 지금 목표 등급과 그 등급의 스크립트 길이(F14.2). 길이 표는 시험 팩이 정해 앱 층이 넣는다. 표에 없는 등급이면 길이가 null 이다. */
@Inject
class ObserveScriptLengthUseCase(private val store: TargetGradeStore, private val lengths: Map<String, ScriptLength>) {
    operator fun invoke(): Flow<Pair<String, ScriptLength?>> = store.targetGrade.map { it to lengths[it] }
}
