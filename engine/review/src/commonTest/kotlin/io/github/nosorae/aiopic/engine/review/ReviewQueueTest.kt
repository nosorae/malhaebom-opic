package io.github.nosorae.aiopic.engine.review

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReviewQueueTest {
    private val now = Instant.parse("2026-11-19T09:00:00Z")

    private fun card(id: Long, dueAt: Instant = now - 1.hours, archived: Boolean = false, createdAt: Instant = now - 2.days) = ExpressionCard(
        id = id,
        kind = CardKind.Formulaic,
        said = "",
        better = "commute",
        meaning = "통근하다",
        errorType = null,
        context = "출근 얘기를 하다가",
        createdAt = createdAt,
        dueAt = dueAt,
        archived = archived,
    )

    /** 지금 기억할 확률이 [daysAgo] 에 따라 달라지도록, 그만큼 전에 알았음으로 한 번 복습한 카드다. */
    private fun reviewed(id: Long, daysAgo: Int) = card(id).copy(firstSeen = false).review(Rating.Good, now - daysAgo.days, ReviewAmount.Normal).copy(dueAt = now - 1.hours)

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
    fun `F18_2 처음 봄 카드는 오늘 새로 보여 준 수와 합쳐 10장까지만 만든 순으로 넣는다`() {
        val cards = (1L..15L).map { card(it, createdAt = now - 2.days + it.hours) } + reviewed(100, daysAgo = 3)

        val queue = todayQueue(cards, now, reviewedToday = 4, newToday = 4)

        assertEquals((1L..6L).toList(), queue.filter { it.firstSeen }.ids())
        assertEquals(7, queue.size)
    }

    @Test
    fun `F18_2 다음 복습 시각에서 하루가 넘게 지난 처음 봄 카드는 10장 상한과 상관없이 맨 앞에 둔다`() {
        val late = card(50, dueAt = now - 2.days)
        val cards = listOf(reviewed(1, daysAgo = 30), card(2)) + late

        assertEquals(listOf(50L, 1L), todayQueue(cards, now, reviewedToday = 0, newToday = 10).ids())
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
    fun `F18_11 로컬 날짜 번호는 기기 시간대에서 새벽 4시에 바뀐다`() {
        // 2026-11-18T18:00Z 는 서울 11-19 03:00 이라 아직 11-18(20775) 이고, 19:00Z 는 서울 04:00 이라 11-19(20776) 이다.
        assertEquals(20775L to 20776L, Instant.parse("2026-11-18T18:00:00Z").localDay(seoul) to Instant.parse("2026-11-18T19:00:00Z").localDay(seoul))
    }

    @Test
    fun `F18_11 다음 날 새벽 4시는 앱의 오늘 다음 날 4시다`() {
        // 서울 11-19 02:00 은 앱의 11-18 이라 다음 날 새벽 4시는 서울 11-19 04:00 이다.
        assertEquals(Instant.parse("2026-11-18T19:00:00Z"), Instant.parse("2026-11-18T17:00:00Z").nextDayStart(seoul))
    }

    @Test
    fun `F18_2 오늘 복습한 수와 새로 보여 준 수를 기기 시간대의 오늘로 세어 뺀다`() {
        // 지금은 서울 11-19 18:00 이다. 앞 둘은 서울의 오늘(04:00 뒤) 봤고 셋째는 서울 11-19 03:00 이라 앱의 어제다.
        // 첫째는 처음 봄에서 다음 버튼만 누른 카드라 복습 횟수가 0 이고 오늘 새로 보여 준 수에 든다.
        fun seenAt(id: Long, at: String, count: Int) = card(id, dueAt = now + 1.days).copy(firstSeen = false, lastReviewedAt = Instant.parse(at), reviewCount = count)
        val done = listOf(seenAt(1, "2026-11-19T00:30:00Z", 0), seenAt(2, "2026-11-18T19:30:00Z", 3), seenAt(3, "2026-11-18T18:00:00Z", 0))
        val due = (10L..29L).map { reviewed(it, daysAgo = 3) } + (100L..114L).map { card(it) }

        val queue = todayQueue(done + due, now, seoul)

        assertEquals(9 to 28, queue.count { it.firstSeen } to queue.size)
    }
}
