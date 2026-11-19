package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 위젯에 보일 값과 날이 바뀔 때의 장면(F19.6, F19.7). 서울 시각으로 센다. */
class ReviewWidgetTest {
    private val seoul = TimeZone.of("Asia/Seoul")

    /** 서울 2026-11-20 금요일 오후 8시. */
    private val friday = Instant.parse("2026-11-20T11:00:00Z")

    /** 서울 2026-11-21 토요일 새벽 4시. */
    private val saturday = Instant.parse("2026-11-20T19:00:00Z")

    private val today = friday.localDay(seoul)

    private fun card(id: Long, dueAt: Instant) = ExpressionCard(
        id = id,
        kind = CardKind.Formulaic,
        said = "",
        better = "commute",
        meaning = "통근하다",
        errorType = null,
        context = "",
        createdAt = dueAt - 1.days,
        dueAt = dueAt,
        firstSeen = false,
    )

    @Test
    fun `F19_6 오늘 남은 장 수와 이번 주 복습한 날 수를 센다`() {
        val cards = listOf(card(1, dueAt = friday - 1.hours), card(2, dueAt = friday - 2.hours), card(3, dueAt = friday + 1.hours))

        val widget = reviewWidgetAt(friday, cards, reviewDays = setOf(today, today - 2, today - 7), timeZone = seoul)

        assertEquals(ReviewWidget(friday, left = 2, weekDays = 2), widget)
    }

    @Test
    fun `F19_7 지금 장면 뒤로 날이 바뀌는 새벽 4시마다 장면을 하나씩 낸다`() {
        val widgets = upcomingReviewWidgets(emptyList(), emptySet(), friday, seoul, days = 3)

        assertEquals(listOf(friday, saturday, saturday + 1.days, saturday + 2.days), widgets.map { it.from })
    }

    @Test
    fun `F19_7 다음 날 장면은 그날 새벽 4시까지 복습 시각이 된 카드를 센다`() {
        val cards = listOf(card(1, dueAt = friday - 1.hours), card(2, dueAt = saturday - 1.hours))

        val widgets = upcomingReviewWidgets(cards, emptySet(), friday, seoul, days = 1)

        assertEquals(listOf(1, 2), widgets.map { it.left })
    }

    @Test
    fun `F19_7 월요일 장면은 이번 주 복습한 날을 다시 센다`() {
        val widgets = upcomingReviewWidgets(emptyList(), reviewDays = setOf(today), now = friday, timeZone = seoul, days = 3)

        assertEquals(listOf(1, 1, 1, 0), widgets.map { it.weekDays })
    }
}
