package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.CoachStore
import io.github.nosorae.aiopic.engine.coach.ScriptWriter
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlinx.coroutines.flow.first

/** 세션의 스크립트를 연다. 없으면 받아쓰기, 메모, 표현 카드로 만들어 저장한다(F14.1). 받아쓰기는 세션에 남아 실패해도 다시 만들 수 있다. */
@Inject
class OpenScriptUseCase(private val store: CoachStore, private val writer: ScriptWriter) {
    suspend operator fun invoke(sessionId: String, question: String): AnalysisResult<CoachScript> {
        store.script(sessionId).first()?.let { return AnalysisResult.Success(it) }
        val session = store.load(sessionId) ?: return AnalysisResult.Failed(AnalysisError.Rejected)
        return writer.write(session, question).also { if (it is AnalysisResult.Success) store.save(it.value) }
    }
}
