package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.CoachState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** 세션을 열고 그 상태를 돌려준다. 저장된 세션이면 이어 가고(F11.8), 없으면 새로 시작한다. 연결은 [scope] 가 끝나면 닫힌다. */
@Inject
class OpenCoachUseCase(private val coach: RunningCoach) {
    suspend operator fun invoke(sessionId: String, questionId: String, question: String, targetGrade: String, scope: CoroutineScope): Flow<CoachState> {
        coach.open(sessionId, questionId, question, targetGrade, scope)
        return coach.state(sessionId)
    }
}
