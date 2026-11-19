package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.REVIEW_BATCH
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.localDay
import io.github.nosorae.aiopic.engine.review.todayQueue
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** 복습 화면이 여는 묶음. [remaining] 은 아직 누르지 않은 카드, [results] 는 이번 묶음에서 이미 누른 결과다. */
data class ReviewSession(val remaining: List<ExpressionCard>, val results: List<Rating?>, val silent: Boolean)

/**
 * 복습 묶음을 연다(F18.3, F18.4, F18.8). 오늘 만든 묶음에 남은 카드가 있으면 그 카드부터 이어 가고, 없거나 [more](6장 더)면 오늘 복습할 카드 앞 6장으로 새 묶음을 만든다.
 * 남은 카드는 아직 누르지 않은 id 가운데 지금 있고 보관하지 않은 카드다.
 */
@Inject
class StartReviewUseCase(private val store: CardStore, private val settings: ReviewSettings, private val clock: Clock, private val timeZone: TimeZone) {
    suspend operator fun invoke(more: Boolean = false): ReviewSession {
        val now = clock.now()
        val today = now.localDay(timeZone)
        val silent = settings.silent()
        val saved = settings.reviewBatch()?.takeUnless { more || it.day != today }
        if (saved != null) {
            val rated = saved.results.map { it.first }.toSet()
            val remaining = saved.cardIds.filterNot { it in rated }.mapNotNull { store.card(it) }.filterNot { it.archived }
            if (remaining.isNotEmpty()) return ReviewSession(remaining, saved.results.map { it.second }, silent)
        }
        val cards = todayQueue(store.cards().first(), now, timeZone).take(REVIEW_BATCH)
        settings.saveReviewBatch(ReviewBatch(today, cards.map { it.id }))
        return ReviewSession(cards, emptyList(), silent)
    }
}
