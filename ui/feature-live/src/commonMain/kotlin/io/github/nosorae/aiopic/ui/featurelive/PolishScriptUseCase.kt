package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.ScriptWriter

/** 고친 판(없으면 원본)을 [targetGrade] 길이로 다듬어 고친 판으로 저장한다(F14.4). 실패하면 아무것도 바꾸지 않는다. */
@Inject
class PolishScriptUseCase(private val writer: ScriptWriter, private val saveScriptEditUseCase: SaveScriptEditUseCase) {
    suspend operator fun invoke(script: CoachScript, question: String, targetGrade: String): AnalysisResult<CoachScript> = when (val result = writer.polish(script.edited ?: script.original, script.expressions, question, targetGrade)) {
        is AnalysisResult.Failed -> result
        is AnalysisResult.Unscorable -> result
        is AnalysisResult.Success -> AnalysisResult.Success(saveScriptEditUseCase(script, result.value))
    }
}
