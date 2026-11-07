package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.CoachStore
import io.github.nosorae.aiopic.engine.coach.ScriptLength
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.coach.ScriptResult
import io.github.nosorae.aiopic.engine.coach.ScriptWriter
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** 세션의 스크립트를 연다. 없으면 받아쓰기, 메모, 표현 카드로 만들어 저장한다(F14.1). 받아쓰기는 세션에 남아 실패해도 다시 만들 수 있다. */
@Inject
class OpenScriptUseCase(private val store: CoachStore, private val writer: ScriptWriter) {
    suspend operator fun invoke(sessionId: String, question: String): ScriptResult<CoachScript> {
        store.script(sessionId).first()?.let { return ScriptResult.Success(it) }
        val session = store.load(sessionId) ?: return ScriptResult.Failed(AnalysisError.Rejected)
        return writer.write(session, question).also { if (it is ScriptResult.Success) store.save(it.value) }
    }
}

/** 고친 판을 고친 시각과 함께 저장한다(F14.3). [edited] 가 null 이면 원본으로 돌린다. */
@Inject
class SaveScriptEditUseCase(private val store: CoachStore, private val clock: Clock) {
    suspend operator fun invoke(script: CoachScript, edited: ScriptParts?): CoachScript = script.copy(edited = edited, editedAt = edited?.let { clock.now() }).also { store.save(it) }
}

/** 고친 판(없으면 원본)을 [targetGrade] 길이로 다듬어 고친 판으로 저장한다(F14.4). 실패하면 아무것도 바꾸지 않는다. */
@Inject
class PolishScriptUseCase(private val writer: ScriptWriter, private val saveScriptEditUseCase: SaveScriptEditUseCase) {
    suspend operator fun invoke(script: CoachScript, question: String, targetGrade: String): ScriptResult<CoachScript> = when (val result = writer.polish(script.edited ?: script.original, script.expressions, question, targetGrade)) {
        is ScriptResult.Failed -> result
        is ScriptResult.Success -> ScriptResult.Success(saveScriptEditUseCase(script, result.value))
    }
}

/** 지금 목표 등급과 그 등급의 스크립트 길이(F14.2). 길이 표는 시험 팩이 정해 앱 층이 넣는다. 표에 없는 등급이면 길이가 null 이다. */
@Inject
class ObserveScriptLengthUseCase(private val store: TargetGradeStore, private val lengths: Map<String, ScriptLength>) {
    operator fun invoke(): Flow<Pair<String, ScriptLength?>> = store.targetGrade.map { it to lengths[it] }
}
