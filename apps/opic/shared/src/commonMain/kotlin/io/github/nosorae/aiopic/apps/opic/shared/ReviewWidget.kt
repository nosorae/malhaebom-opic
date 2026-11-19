package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import io.github.nosorae.aiopic.engine.review.nextDayStart
import io.github.nosorae.aiopic.engine.review.reviewDaysThisWeek
import io.github.nosorae.aiopic.engine.review.todayQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.getString
import kotlin.time.Instant

/** 오늘 복습 위젯의 한 장면(F19.6). [from] 부터 오늘 남은 장 수 [left] 와 이번 주 복습한 날 수 [weekDays] 를 보인다. */
data class ReviewWidget(val from: Instant, val left: Int, val weekDays: Int) {
    /** 할 카드가 없으면 오늘 복습 끝 문구다. */
    suspend fun title(): String = if (left == 0) getString(Res.string.review_widget_done) else getString(Res.string.review_widget_left, left)

    suspend fun text(): String = getString(Res.string.review_widget_week, weekDays)
}

/** [at] 에 위젯이 보일 값. 연습 탭의 오늘 복습 카드와 같은 계산이다(F18.10). */
fun reviewWidgetAt(at: Instant, cards: List<ExpressionCard>, reviewDays: Set<Long>, timeZone: TimeZone): ReviewWidget = ReviewWidget(at, todayQueue(cards, at, timeZone).size, reviewDaysThisWeek(reviewDays, at.localDay(timeZone)))

/**
 * 지금 장면과 앞으로 [days] 번 날이 바뀌는 새벽 4시의 장면(F19.7). 그 사이 복습하지 않는다고 보고 센다.
 * iOS 위젯은 앱 코드를 돌릴 수 없어서 이 목록을 미리 넘겨 날이 바뀌면 다음 장면으로 넘어간다.
 */
fun upcomingReviewWidgets(cards: List<ExpressionCard>, reviewDays: Set<Long>, now: Instant, timeZone: TimeZone, days: Int): List<ReviewWidget> = generateSequence(now) { it.nextDayStart(timeZone) }.take(days + 1).map { reviewWidgetAt(it, cards, reviewDays, timeZone) }.toList()

/**
 * 복습을 끝내거나(카드와 복습한 날이 바뀜) 연습 정리가 끝날 때(카드가 늘어남) 지금 장면을 다시 낸다(F19.7).
 * 평가 하나마다 카드 줄이 바뀌므로 묶음 중간에도 낸다. 값이 같으면 내지 않는다.
 */
fun AppGraph.reviewWidget(): Flow<ReviewWidget> = combine(cardStore.cards(), cardStore.reviewDays()) { cards, days -> reviewWidgetAt(clock.now(), cards, days, timeZone) }.distinctUntilChanged { old, new -> old.left == new.left && old.weekDays == new.weekDays }

/** 다음에 날이 바뀌는 새벽 4시. Android 위젯이 이때 다시 그린다(F19.7). */
fun AppGraph.nextDayStart(): Instant = clock.now().nextDayStart(timeZone)
