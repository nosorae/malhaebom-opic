package io.github.nosorae.aiopic.engine.review

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReviewQueueTest {
    private val now = Instant.parse("2026-11-19T09:00:00Z")

    private fun card(id: Long, dueAt: Instant = now - 1.hours, archived: Boolean = false) = ExpressionCard(
        id = id,
        kind = CardKind.Word,
        said = "",
        better = "commute",
        meaning = "통근하다",
        errorType = null,
        context = "출근 얘기를 하다가",
        createdAt = now - 2.days,
        dueAt = dueAt,
        archived = archived,
    )

    /** 지금 기억할 확률이 [daysAgo] 에 따라 달라지도록, 그만큼 전에 알았음으로 한 번 복습한 카드다. */
    private fun reviewed(id: Long, daysAgo: Int) = card(id).review(Rating.Good, now - daysAgo.days, ReviewAmount.Normal).copy(dueAt = now - 1.hours)

    private fun List<ExpressionCard>.ids() = map { it.id }

    @Test
    fun `F18_2 보관한 카드와 다음 복습 시각이 안 된 카드는 빠진다`() {
        val cards = listOf(card(1), card(2, archived = true), card(3, dueAt = now + 1.hours))

        assertEquals(listOf(1L), todayQueue(cards, now, reviewedToday = 0, newToday = 0).ids())
    }

    @Test
    fun `F18_2 잊었을 확률이 높은 카드가 앞에 온다`() {
        val cards = listOf(reviewed(1, daysAgo = 2), reviewed(2, daysAgo = 30), reviewed(3, daysAgo = 10))

        assertEquals(listOf(2L, 3L, 1L), todayQueue(cards, now, reviewedToday = 0, newToday = 0).ids())
    }

    @Test
    fun `F18_2 하루 30장에서 오늘 이미 복습한 수를 뺀 만큼만 낸다`() {
        val cards = (1L..40L).map { reviewed(it, daysAgo = 3) }

        assertEquals(18, todayQueue(cards, now, reviewedToday = 12, newToday = 0).size)
    }

    @Test
    fun `F18_2 새 카드는 오늘 새로 넣은 수와 합쳐 10장까지만 넣는다`() {
        val cards = (1L..15L).map { card(it) } + reviewed(100, daysAgo = 3)

        val queue = todayQueue(cards, now, reviewedToday = 4, newToday = 4)

        assertEquals(6, queue.count { it.reviewCount == 0 })
        assertEquals(7, queue.size)
    }

    @Test
    fun `F18_4 복습 한 묶음은 6장이다`() {
        assertEquals(6, REVIEW_BATCH)
    }

    @Test
    fun `F18_2 남은 시간은 장 수에 20초를 곱해 분으로 올린다`() {
        assertEquals(listOf(0, 1, 1, 2, 10), listOf(0, 1, 3, 4, 30).map(::minutesLeft))
    }

    @Test
    fun `F18_9 이번 주 복습한 날은 월요일부터 오늘까지 센다`() {
        // 2026-11-15 일요일은 20772, 11-16 월요일은 20773, 11-19 목요일은 20776 번째 날이다(1970-01-01 이 0).
        val days = setOf(20765L, 20772L, 20773L, 20775L, 20776L)

        assertEquals(3, reviewDaysThisWeek(days, today = 20776L))
    }

    @Test
    fun `F18_9 오늘이 월요일이면 오늘만 센다`() {
        assertEquals(1, reviewDaysThisWeek(setOf(20772L, 20773L), today = 20773L))
    }

    private val seoul = TimeZone.of("Asia/Seoul")

    @Test
    fun `로컬 날짜 번호는 기기 시간대의 날짜를 1970-01-01 부터 센다`() {
        // 2026-11-18T16:00Z 는 서울 시각 11-19 01:00 이다.
        assertEquals(20776L, Instant.parse("2026-11-18T16:00:00Z").localDay(seoul))
    }

    @Test
    fun `F18_2 오늘 복습한 수와 새로 넣은 수를 기기 시간대의 오늘로 세어 뺀다`() {
        // 지금은 서울 시각 11-19 18:00 이다. 앞 둘은 서울의 오늘 복습했고 셋째는 서울의 어제 복습했다.
        fun reviewedAt(id: Long, at: String, count: Int) = card(id, dueAt = now + 1.days).copy(lastReviewedAt = Instant.parse(at), reviewCount = count)
        val done = listOf(reviewedAt(1, "2026-11-19T00:30:00Z", 1), reviewedAt(2, "2026-11-18T16:00:00Z", 3), reviewedAt(3, "2026-11-18T14:00:00Z", 1))
        val due = (10L..29L).map { reviewed(it, daysAgo = 3) } + (100L..114L).map { card(it) }

        val queue = todayQueue(done + due, now, seoul)

        assertEquals(9 to 28, queue.count { it.reviewCount == 0 } to queue.size)
    }
}
