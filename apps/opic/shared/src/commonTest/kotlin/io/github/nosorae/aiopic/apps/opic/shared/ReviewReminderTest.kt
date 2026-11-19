package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 복습 알림을 보낼지와 언제 보낼지(F19.3, F19.4). 서울 시각으로 센다. */
class ReviewReminderTest {
    private val seoul = TimeZone.of("Asia/Seoul")

    /** 서울 2026-11-20 오후 8시. */
    private val eight = Instant.parse("2026-11-20T11:00:00Z")

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
    fun `F19_3 복습할 카드가 있고 오늘 아직 복습하지 않았으면 장 수와 분을 싣는다`() {
        val cards = (1L..4L).map { card(it, dueAt = eight - 1.hours) }

        val reminder = reviewReminderAt(eight, cards, reviewDays = emptySet(), timeZone = seoul)

        assertEquals(ReviewReminder(eight, cards = 4), reminder)
        assertEquals(2, reminder?.minutes)
    }

    @Test
    fun `F19_3 오늘 복습할 카드가 없으면 보내지 않는다`() {
        val cards = listOf(card(1, dueAt = eight + 1.hours))

        assertNull(reviewReminderAt(eight, cards, reviewDays = emptySet(), timeZone = seoul))
    }

    @Test
    fun `F19_3 오늘 이미 복습했으면 보내지 않는다`() {
        val cards = listOf(card(1, dueAt = eight - 1.hours))

        assertNull(reviewReminderAt(eight, cards, reviewDays = setOf(eight.localDay(seoul)), timeZone = seoul))
    }

    @Test
    fun `F18_11 새벽 1시 알림은 전날로 쳐서 전날 밤에 복습했으면 보내지 않는다`() {
        val one = Instant.parse("2026-11-20T16:00:00Z")
        val cards = listOf(card(1, dueAt = one - 1.hours))

        assertNull(reviewReminderAt(one, cards, reviewDays = setOf(eight.localDay(seoul)), timeZone = seoul))
    }

    @Test
    fun `F19_3 알림 시각 전이면 오늘 그 시각부터 지났으면 내일 그 시각부터 하루에 하나다`() {
        val morning = Instant.parse("2026-11-20T00:00:00Z")

        assertEquals(listOf(eight, eight + 1.days), reminderTimes(morning, LocalTime(20, 0), seoul, count = 2))
        assertEquals(listOf(eight + 1.days), reminderTimes(eight, LocalTime(20, 0), seoul, count = 1))
    }

    @Test
    fun `F19_4 미리 걸 알림은 오늘 이미 복습했으면 내일 것부터이고 그날 복습할 카드 수를 싣는다`() {
        val now = eight - 2.hours
        val cards = listOf(card(1, dueAt = now - 1.hours), card(2, dueAt = eight + 10.hours))

        val reminders = upcomingReviewReminders(cards, reviewDays = setOf(now.localDay(seoul)), now = now, time = LocalTime(20, 0), timeZone = seoul, days = 3)

        assertEquals(listOf(ReviewReminder(eight + 1.days, 2), ReviewReminder(eight + 2.days, 2)), reminders)
    }
}
