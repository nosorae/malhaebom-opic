package io.github.nosorae.aiopic.engine.review

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 복습 한 묶음 장 수(F18.4). */
const val REVIEW_BATCH = 6
private const val DAILY_LIMIT = 30
private const val DAILY_NEW_LIMIT = 10
private const val SECONDS_PER_CARD = 20

/**
 * 오늘 복습할 카드(F18.2). 보관하지 않았고 다음 복습 시각이 지난 카드를 기억할 확률이 낮은 순으로 세운다.
 * 하루 30장에서 [reviewedToday] 를 빼 자르고, 새 카드는 [newToday] 와 합쳐 10장까지만 넣는다.
 * 오늘이 언제 시작하는지는 기기 시간대에 달려 있어서 두 수는 부르는 쪽이 센다.
 */
fun todayQueue(cards: List<ExpressionCard>, now: Instant, reviewedToday: Int, newToday: Int): List<ExpressionCard> {
    var newLeft = DAILY_NEW_LIMIT - newToday
    return cards.asSequence()
        .filter { !it.archived && it.dueAt <= now }
        .sortedBy { it.retrievability(now) }
        .filter { it.reviewCount > 0 || newLeft-- > 0 }
        .take((DAILY_LIMIT - reviewedToday).coerceAtLeast(0))
        .toList()
}

/**
 * 오늘 복습할 카드를 [timeZone] 의 오늘로 센 두 수로 고른다(F18.2). 오늘 복습한 카드 수가 오늘 복습한 수이고,
 * 그 가운데 복습 횟수가 1 인 카드가 오늘 새로 넣은 수다. 평가하면 다음 복습이 하루 이상 뒤라 한 카드는 하루에 한 번만 복습한다.
 */
fun todayQueue(cards: List<ExpressionCard>, now: Instant, timeZone: TimeZone): List<ExpressionCard> {
    val today = now.localDay(timeZone)
    val reviewed = cards.filter { it.lastReviewedAt?.localDay(timeZone) == today }
    return todayQueue(cards, now, reviewedToday = reviewed.size, newToday = reviewed.count { it.reviewCount == 1 })
}

/** [timeZone] 에서 이 시각의 날짜를 1970-01-01 을 0 으로 세어 돌려준다. */
fun Instant.localDay(timeZone: TimeZone): Long = toLocalDateTime(timeZone).date.toEpochDays()

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
