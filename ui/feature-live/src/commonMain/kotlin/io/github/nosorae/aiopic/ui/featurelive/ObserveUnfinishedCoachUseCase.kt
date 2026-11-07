package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.CoachStatus
import io.github.nosorae.aiopic.engine.coach.CoachStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 가장 최근에 시작한 끝나지 않은 세션(F11.8). 나가거나 앱이 꺼진 대화를 시작 시트에서 이어 간다. 없으면 null 이다. */
@Inject
class ObserveUnfinishedCoachUseCase(private val store: CoachStore) {
    operator fun invoke(): Flow<CoachState?> = store.sessions().map { sessions -> sessions.firstOrNull { (session, _) -> session.status != CoachStatus.Finished }?.first }
}
