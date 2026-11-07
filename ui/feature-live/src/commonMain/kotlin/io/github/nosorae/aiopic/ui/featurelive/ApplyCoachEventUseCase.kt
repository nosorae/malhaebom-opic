package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.CoachEvent

/** 사용자가 한 일을 [sessionId] 세션에 넣는다. 저장과 연결 열고 닫기는 엔진이 한다(F11, F12). */
@Inject
class ApplyCoachEventUseCase(private val coach: RunningCoach) {
    suspend operator fun invoke(sessionId: String, event: CoachEvent) = coach.send(sessionId, event)
}
