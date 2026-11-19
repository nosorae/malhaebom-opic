package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
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

    private val clock = object : Clock {
        override fun now() = friday
    }

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

        assertEquals(listOf(1, 2, 2), widgets.map { it.left })
    }

    @Test
    fun `F19_7 월요일 장면은 이번 주 복습한 날을 다시 센다`() {
        val widgets = upcomingReviewWidgets(emptyList(), reviewDays = setOf(today), now = friday, timeZone = seoul, days = 3)

        assertEquals(listOf(1, 1, 1, 0), widgets.map { it.weekDays })
    }

    @Test
    fun `F19_6 낮에 복습 시각이 되는 카드는 그 시각 장면부터 센다`() {
        val noon = saturday + 8.hours
        val cards = listOf(card(1, dueAt = friday + 1.hours), card(2, dueAt = noon))

        val widgets = upcomingReviewWidgets(cards, emptySet(), friday, seoul, days = 2)

        assertEquals(listOf(friday, friday + 1.hours, saturday, noon, saturday + 1.days), widgets.map { it.from })
        assertEquals(listOf(0, 1, 1, 2, 2), widgets.map { it.left })
    }

    @Test
    fun `F19_7 Android 위젯은 다음 새벽 4시 전에 카드가 복습 시각이 되면 그때 다시 그린다`() {
        val widgets = upcomingReviewWidgets(listOf(card(1, dueAt = friday + 1.hours)), emptySet(), friday, seoul, days = 1)

        assertEquals(friday + 1.hours, widgets[1].from)
    }

    @Test
    fun `F19_7 카드가 늘거나 평가해 복습한 날이 바뀌면 위젯 장면을 다시 낸다`() = runTest {
        val cards = MutableStateFlow(emptyList<ExpressionCard>())
        val days = MutableStateFlow(emptySet<Long>())
        val emitted = async(start = CoroutineStart.UNDISPATCHED) { reviewWidgets(cards, days, clock, seoul).take(4).toList() }
        testScheduler.runCurrent()

        // 연습 정리로 카드가 늘고, 평가해 다음 복습 시각이 미뤄진 뒤 그날이 복습한 날로 남는다.
        cards.value = listOf(card(1, dueAt = friday - 1.hours))
        testScheduler.runCurrent()
        cards.value = listOf(card(1, dueAt = friday + 2.days))
        testScheduler.runCurrent()
        days.value = setOf(today)

        assertEquals(listOf(ReviewWidget(friday, 0, 0), ReviewWidget(friday, 1, 0), ReviewWidget(friday, 0, 0), ReviewWidget(friday, 0, 1)), emitted.await().map { it.first() })
    }

    @Test
    fun `F19_7 값이 앞 장면과 같아도 거르지 않는다`() = runTest {
        val cards = MutableStateFlow(listOf(card(1, dueAt = friday - 1.hours)))
        val emitted = async(start = CoroutineStart.UNDISPATCHED) { reviewWidgets(cards, MutableStateFlow(emptySet()), clock, seoul).take(2).toList() }
        testScheduler.runCurrent()

        cards.value = cards.value + card(2, dueAt = friday + 3.days)

        assertEquals(listOf(1, 1), emitted.await().map { it.first().left })
    }
}
