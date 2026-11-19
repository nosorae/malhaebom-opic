package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.Rating
import kotlin.time.Clock

/** 이미 알아요(F18.3). 카드를 빼지 않고 FSRS 쉬움으로 평가해 간격만 늘린다. */
@Inject
class KnowExpressionUseCase(private val store: CardStore, private val settings: ReviewSettings, private val clock: Clock) {
    suspend operator fun invoke(id: Long) = store.rate(id, Rating.Easy, clock.now(), settings.reviewAmount())
}
