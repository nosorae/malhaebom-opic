package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 연습 탭 맨 위 카드 둘(F16.1, F18.10). */
class SpeakingCardsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val seoul = TimeZone.of("Asia/Seoul")

    // 서울 2026-11-20 18시.
    private val now = Instant.parse("2026-11-20T09:00:00Z")
    private val control = FakePracticeControl()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(SpeakingStore, CardStore) -> Unit) = runTest(dispatcher) {
        val db = inMemoryDatabase(dispatcher)
        try {
            block(SpeakingStore(db), CardStore(db, seoul))
        } finally {
            db.close()
        }
    }

    private fun viewModel(speaking: SpeakingStore, cards: CardStore) = SpeakingCardsViewModel(ObserveSpeakingCardsUseCase(control, speaking, cards, FakeSpeakingSettings(minutes = 20), FixedClock(now), seoul))

    private fun practice(id: String, at: Instant) = SpeakingState(id, at, 20.minutes, AudioRoute.Earphones, "cooking", finished = true)

    @Test
    fun `F16_1 말하기 연습 카드는 기본 연습 시간과 지난 연습 날짜를 보인다`() = test { speaking, cards ->
        speaking.finish(practice("p1", now - 3.days))
        // 서울 새벽 2시에 끝낸 연습은 전날로 친다(F18.11).
        speaking.finish(practice("p2", Instant.parse("2026-11-18T17:00:00Z")))
        val viewModel = viewModel(speaking, cards)

        advanceUntilIdle()

        assertEquals(20 to LocalDate(2026, 11, 18), viewModel.uiState.value.defaultMinutes to viewModel.uiState.value.lastPractice)
    }

    @Test
    fun `F18_10 오늘 복습 카드는 오늘 복습할 카드 수와 남은 분을 보인다`() = test { speaking, cards ->
        repeat(4) { cards.save(ExpressionCard(kind = CardKind.Formulaic, said = "s$it", better = "b$it", meaning = "m", errorType = null, context = "c", createdAt = now - 2.days, dueAt = now - 1.days, firstSeen = false)) }
        val viewModel = viewModel(speaking, cards)

        advanceUntilIdle()

        assertEquals(4 to 2, viewModel.uiState.value.todayCount to viewModel.uiState.value.minutesLeft)
    }

    @Test
    fun `연습이 도는 중이면 카드가 연습으로 돌아가기를 보인다`() = test { speaking, cards ->
        control.state.value = practice("p1", now).copy(finished = false)
        val viewModel = viewModel(speaking, cards)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.running)
    }
}
