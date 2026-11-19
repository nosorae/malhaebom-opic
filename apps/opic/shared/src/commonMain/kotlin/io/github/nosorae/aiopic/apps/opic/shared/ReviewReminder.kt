package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import io.github.nosorae.aiopic.engine.review.minutesLeft
import io.github.nosorae.aiopic.engine.review.todayQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.getString
import kotlin.time.Instant

/** 복습 알림을 누르면 여는 링크(F19.5). Android 매니페스트와 iOS Info.plist 에 같은 scheme 을 등록한다. */
const val REVIEW_LINK = "malhaebom://review"

/** 앱 밖에서 들어온 링크가 여는 화면. 모르는 링크면 null 이다. */
fun routeFromLink(link: String): AppRoute? = AppRoute.Review.takeIf { link == REVIEW_LINK }

/** [at] 에 보낼 복습 알림 하나(F19.3). 본문은 장 수와 대략의 분만 쓴다. */
data class ReviewReminder(val at: Instant, val cards: Int) {
    val minutes: Int get() = minutesLeft(cards)

    suspend fun title(): String = getString(Res.string.review_reminder_title)

    suspend fun text(): String = getString(Res.string.review_reminder_text, cards, minutes)
}

/**
 * [at] 에 복습 알림을 보낼지(F19.3). 그 시각이 속한 앱의 하루(새벽 4시에 바뀜)에 이미 복습했거나 그 시각에 오늘 복습할 카드가 없으면 null 이다.
 * 앱을 보고 있는지는 플랫폼이 띄울 때 본다.
 */
fun reviewReminderAt(at: Instant, cards: List<ExpressionCard>, reviewDays: Set<Long>, timeZone: TimeZone): ReviewReminder? {
    if (at.localDay(timeZone) in reviewDays) return null
    val count = todayQueue(cards, at, timeZone).size
    return if (count == 0) null else ReviewReminder(at, count)
}

/** [now] 뒤로 오는 [time] 시각 [count] 개. 기기 시간대의 날짜마다 하나다. */
fun reminderTimes(now: Instant, time: LocalTime, timeZone: TimeZone, count: Int): List<Instant> {
    val today = now.toLocalDateTime(timeZone).date
    return generateSequence(today.toEpochDays()) { it + 1 }
        .map { LocalDate.fromEpochDays(it).atTime(time).toInstant(timeZone) }
        .filter { it > now }
        .take(count)
        .toList()
}

/**
 * 앞으로 [days] 날 동안 보낼 복습 알림(F19.4). iOS 는 알림을 보낼 때 앱 코드를 돌릴 수 없어서 이 목록을 미리 걸어 둔다.
 * 그 사이 복습하지 않는다고 보고 센다. 복습하거나 카드가 바뀌면 다시 계산해 바꿔 건다.
 */
fun upcomingReviewReminders(cards: List<ExpressionCard>, reviewDays: Set<Long>, now: Instant, time: LocalTime, timeZone: TimeZone, days: Int): List<ReviewReminder> = reminderTimes(now, time, timeZone, days).mapNotNull { reviewReminderAt(it, cards, reviewDays, timeZone) }

/** 지금 보낼 복습 알림. Android 는 알림 시각에 앱을 깨워 이 값으로 보낼지 정한다. */
suspend fun AppGraph.reviewReminderNow(): ReviewReminder? = reviewReminderAt(clock.now(), cardStore.cards().first(), cardStore.reviewDays().first(), timeZone)

/** 다음 복습 알림 시각. 알림이 꺼져 있으면 null 이다. */
fun AppGraph.nextReviewReminderTime(): Instant? = settings.speaking.value.takeIf { it.reminderOn }?.let { reminderTimes(clock.now(), it.reminderAt, timeZone, count = 1).first() }

/** 복습 알림을 켜고 끄거나 시각을 바꿀 때마다 다음 알림 시각을 낸다. Android 는 이 값으로 알람을 다시 건다. */
fun AppGraph.reviewReminderSchedule(): Flow<Instant?> = settings.speaking.map { it.reminderOn to it.reminderAt }.distinctUntilChanged().map { nextReviewReminderTime() }
