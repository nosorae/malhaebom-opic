package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.coach.CoachEvent
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.LiveCoach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 지금 대화 화면이 돌리는 코치 하나. 대화 화면은 한 번에 하나만 열려서 앱에 하나 둔다.
 * 화면 ViewModel 은 엔진 코치를 들지 않고 UseCase 가 세션 id 로 이것을 거쳐 일을 넘긴다. 모의고사 녹음기를 UseCase 가 주입받아 쓰는 것과 같다.
 */
class RunningCoach(private val factory: CoachFactory) {
    private val coach = MutableStateFlow<LiveCoach?>(null)

    /** 저장된 세션이면 이어 가고(F11.8), 없으면 새로 시작한다. 코치는 [scope] 가 끝나면 연결을 닫는다. */
    suspend fun open(sessionId: String, questionId: String, question: String, targetGrade: String, scope: CoroutineScope) {
        coach.value = factory.create(question, targetGrade, scope).also { if (!it.open(sessionId)) it.start(sessionId, questionId, question, targetGrade) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun state(sessionId: String): Flow<CoachState> = coach.filterNotNull().flatMapLatest { it.state }.filterNotNull().filter { it.id == sessionId }

    /** 다른 세션의 코치가 돌고 있으면 버린다. */
    suspend fun send(sessionId: String, event: CoachEvent) {
        coach.value?.takeIf { it.state.value?.id == sessionId }?.send(event)
    }
}
