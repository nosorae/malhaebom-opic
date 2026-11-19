package io.github.nosorae.aiopic.engine.review

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 복습 한 묶음 장 수(F18.4). */
const val REVIEW_BATCH = 6
private const val DAILY_LIMIT = 30
private const val DAILY_NEW_LIMIT = 10
private const val SECONDS_PER_CARD = 20

/** 앱의 하루가 바뀌는 시각(F18.11). 자정에서 새벽 4시 사이에 한 연습과 복습은 전날로 친다. */
private val DAY_START = 4.hours

/**
 * 오늘 복습할 카드(F18.2). 보관하지 않았고 다음 복습 시각이 지난 카드를 기억할 확률이 낮은 순으로 세운다.
 * 처음 봄 카드는 [newToday] 와 합쳐 10장까지만 만든 순으로 넣고, 다음 복습 시각에서 하루가 넘게 지난 처음 봄 카드는 상한 없이 맨 앞에 둔다.
 * 하루 30장에서 [reviewedToday] 를 빼 자른다. 오늘이 언제 시작하는지는 기기 시간대에 달려 있어서 두 수는 부르는 쪽이 센다.
 */
fun todayQueue(cards: List<ExpressionCard>, now: Instant, reviewedToday: Int, newToday: Int): List<ExpressionCard> {
    val due = cards.filter { !it.archived && it.dueAt <= now }
    val (fresh, seen) = due.partition { it.firstSeen }
    val (late, onTime) = fresh.sortedBy { it.createdAt }.partition { now - it.dueAt > 1.days }
    val picked = onTime.take((DAILY_NEW_LIMIT - newToday).coerceAtLeast(0))
    return (late + (picked + seen).sortedBy { it.retrievability(now) }).take((DAILY_LIMIT - reviewedToday).coerceAtLeast(0))
}

/**
 * 오늘 복습할 카드를 [timeZone] 의 오늘로 센 두 수로 고른다(F18.2). 마지막 복습 시각이 오늘인 카드 수가 오늘 복습한 수이고,
 * 그 가운데 복습 횟수가 0 인 카드(처음 봄에서 다음만 누른 카드)가 오늘 새로 보여 준 수다. 본 카드는 다음 복습이 다음 날 이후라 하루에 한 번만 나온다.
 */
fun todayQueue(cards: List<ExpressionCard>, now: Instant, timeZone: TimeZone): List<ExpressionCard> {
    val today = now.localDay(timeZone)
    val reviewed = cards.filter { it.lastReviewedAt?.localDay(timeZone) == today }
    return todayQueue(cards, now, reviewedToday = reviewed.size, newToday = reviewed.count { it.reviewCount == 0 })
}

/** [timeZone] 에서 이 시각이 속한 앱의 하루를 1970-01-01 을 0 으로 세어 돌려준다. 하루는 새벽 4시에 바뀐다(F18.11). */
fun Instant.localDay(timeZone: TimeZone): Long = (this - DAY_START).toLocalDateTime(timeZone).date.toEpochDays()

/** 이 시각이 속한 앱의 하루 다음 날 새벽 4시. 새 카드의 첫 복습 시각이다(F17.5, F16.23). */
fun Instant.nextDayStart(timeZone: TimeZone): Instant = LocalDate.fromEpochDays(localDay(timeZone) + 1).atTime(LocalTime(DAY_START.inWholeHours.toInt(), 0)).toInstant(timeZone)

/** 남은 분. 장 수에 20초를 곱해 분으로 올린다(F18.2). */
fun minutesLeft(cardCount: Int): Int = (cardCount * SECONDS_PER_CARD + 59) / 60

/**
 * 이번 주 복습한 날 수(F18.9). 날은 1970-01-01 을 0 으로 센 로컬 날짜 번호다.
 * 1970-01-01 이 목요일이라 (날 + 3) 을 7 로 나눈 나머지가 월요일부터 지난 날수다.
 */
fun reviewDaysThisWeek(reviewDays: Set<Long>, today: Long): Int {
    val monday = today - (today + 3).mod(7)
    return reviewDays.count { it in monday..today }
}
