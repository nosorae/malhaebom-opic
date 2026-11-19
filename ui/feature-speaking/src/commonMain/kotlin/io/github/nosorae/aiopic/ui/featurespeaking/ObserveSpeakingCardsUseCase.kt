package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.localDay
import io.github.nosorae.aiopic.engine.review.reviewDaysThisWeek
import io.github.nosorae.aiopic.engine.review.todayQueue
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** 연습 탭 맨 위 카드 둘의 값(F16.1, F18.10). [lastPractice] 는 앱의 하루(F18.11)로 센 날짜다. */
data class SpeakingCards(
    val defaultMinutes: Int,
    val lastPractice: LocalDate?,
    val running: Boolean,
    val todayCount: Int,
    val weekDays: Int,
    val hasCards: Boolean,
)

/** 연습, 연습 기록, 카드, 복습한 날이 바뀔 때마다 지금 시각으로 다시 센다. */
@Inject
class ObserveSpeakingCardsUseCase(
    private val control: PracticeControl,
    private val speaking: SpeakingStore,
    private val cards: CardStore,
    private val settings: SpeakingSettings,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {
    operator fun invoke(): Flow<SpeakingCards> = combine(flow { emit(settings.defaultMinutes()) }, control.state, speaking.sessions(), cards.cards(), cards.reviewDays()) { minutes, practice, sessions, all, days ->
        val now = clock.now()
        SpeakingCards(
            defaultMinutes = minutes,
            lastPractice = sessions.maxOfOrNull { it.startedAt }?.let { LocalDate.fromEpochDays(it.localDay(timeZone)) },
            running = practice?.finished == false,
            todayCount = todayQueue(all, now, timeZone).size,
            weekDays = reviewDaysThisWeek(days, now.localDay(timeZone)),
            hasCards = all.isNotEmpty(),
        )
    }
}
