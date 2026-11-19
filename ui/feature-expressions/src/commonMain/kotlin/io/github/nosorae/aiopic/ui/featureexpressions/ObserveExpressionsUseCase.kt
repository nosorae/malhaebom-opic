package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import io.github.nosorae.aiopic.engine.review.reviewDaysThisWeek
import io.github.nosorae.aiopic.engine.review.todayQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** 모든 카드(새로 만든 순), 오늘 복습할 장 수, 이번 주 복습한 날 수(F18.1, F18.2, F18.9). */
data class ExpressionsOverview(val cards: List<ExpressionCard>, val todayCount: Int, val weekDays: Int)

/** 카드나 복습한 날이 바뀔 때마다 지금 시각으로 다시 센다. */
@Inject
class ObserveExpressionsUseCase(private val store: CardStore, private val clock: Clock, private val timeZone: TimeZone) {
    operator fun invoke(): Flow<ExpressionsOverview> = combine(store.cards(), store.reviewDays()) { cards, days ->
        val now = clock.now()
        ExpressionsOverview(cards, todayQueue(cards, now, timeZone).size, reviewDaysThisWeek(days, now.localDay(timeZone)))
    }
}
