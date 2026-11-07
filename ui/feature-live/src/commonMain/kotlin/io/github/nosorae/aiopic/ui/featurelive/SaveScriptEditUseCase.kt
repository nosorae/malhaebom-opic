package io.github.nosorae.aiopic.ui.featurelive

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.CoachStore
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import kotlin.time.Clock

/** 고친 판을 고친 시각과 함께 저장한다(F14.3). [edited] 가 null 이면 원본으로 돌린다. */
@Inject
class SaveScriptEditUseCase(private val store: CoachStore, private val clock: Clock) {
    suspend operator fun invoke(script: CoachScript, edited: ScriptParts?): CoachScript = script.copy(edited = edited, editedAt = edited?.let { clock.now() }).also { store.save(it) }
}
