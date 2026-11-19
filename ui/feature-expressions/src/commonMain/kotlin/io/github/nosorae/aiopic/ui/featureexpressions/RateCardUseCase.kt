package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.Rating
import kotlin.time.Clock

/**
 * 복습 카드 하나를 누른 대로 카드 줄에 바로 쓰고 이번 묶음의 결과에 더한다(F18.6, F18.8).
 * [rating] 이 null 이면 처음 봄 카드의 다음이라 평가 없이 다음 날 새벽 4시로 옮긴다.
 */
@Inject
class RateCardUseCase(private val store: CardStore, private val settings: ReviewSettings, private val clock: Clock) {
    suspend operator fun invoke(id: Long, rating: Rating?) {
        val now = clock.now()
        if (rating == null) store.showNext(id, now) else store.rate(id, rating, now, settings.reviewAmount())
        settings.reviewBatch()?.let { settings.saveReviewBatch(it.copy(results = it.results + (id to rating))) }
    }
}
