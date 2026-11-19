package io.github.nosorae.aiopic.ui.featurespeaking

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.ReviewAmount
import io.github.nosorae.aiopic.engine.review.review
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.OralReview
import io.github.nosorae.aiopic.engine.speaking.ReviewValues
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 연습 정리 화면(F17.6, F17.7, F17.10). 판정 바꾸기와 신고가 실제 연습 기록과 카드 줄(메모리 DB)에 적히는지 본다. */
class SpeakingSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val seoul = TimeZone.of("Asia/Seoul")
    private val startedAt = Instant.parse("2026-11-19T09:00:00Z")
    private val judgedAt = startedAt + 1.minutes

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Stores(val db: OpicDatabase, val cards: CardStore, val speaking: SpeakingStore)

    private fun test(block: suspend TestScope.(Stores) -> Unit) = runTest(dispatcher) {
        val db = inMemoryDatabase(dispatcher)
        try {
            block(Stores(db, CardStore(db, seoul), SpeakingStore(db)))
        } finally {
            db.close()
        }
    }

    private fun viewModel(stores: Stores, retried: MutableList<String> = mutableListOf()) = SpeakingSummaryViewModel(
        practiceId = "p1",
        observeSpeakingSummaryUseCase = ObserveSpeakingSummaryUseCase(stores.speaking, stores.cards),
        changeJudgmentUseCase = ChangeJudgmentUseCase(stores.speaking, stores.cards, FakeReviewSettings()),
        removeCardUseCase = RemoveCardUseCase(stores.cards),
        reportCardUseCase = ReportCardUseCase(stores.speaking, stores.cards),
        retryCardsUseCase = RetryCardsUseCase { retried += it },
    )

    private fun card(practiceId: String? = "p1") = ExpressionCard(
        kind = CardKind.Correction,
        said = "I go to the park yesterday.",
        better = "I went to the park yesterday.",
        meaning = "어제 공원에 갔다.",
        errorType = ErrorType.Tense,
        context = "주말 이야기",
        createdAt = startedAt - 3.days,
        dueAt = startedAt - 1.days,
        practiceId = practiceId,
    )

    /** 말로 복습에서 [rating] 으로 판정된 카드. 코치 판정처럼 판정 전 값을 남기고 카드 줄에 평가를 쓴다(F16.8). */
    private suspend fun judged(stores: Stores, rating: Rating): Pair<ExpressionCard, OralReview> {
        val seen = card(practiceId = "old").review(Rating.Good, startedAt - 3.days, ReviewAmount.Normal).copy(firstSeen = false)
        val id = stores.cards.save(seen)
        val before = stores.cards.card(id)!!
        stores.cards.rate(id, rating, judgedAt, ReviewAmount.Normal)
        val review = OralReview(id, before.better, rating == Rating.Good, judgedAt.toEpochMilliseconds(), ReviewValues(before.difficulty, before.stability, before.dueAt.toEpochMilliseconds(), before.lastReviewedAt?.toEpochMilliseconds()))
        return before to review
    }

    private suspend fun finish(stores: Stores, reviews: List<OralReview> = emptyList()) = stores.speaking.finish(
        SpeakingState("p1", startedAt, 20.minutes, AudioRoute.Earphones, "cooking", talkTime = 20.minutes, userTalk = 9.minutes, coachTalk = 3.minutes, oralReviews = reviews, finished = true),
    )

    @Test
    fun `F17_6 요약에 대화 시간과 내가 말한 시간과 내 말 비율을 보인다`() = test { stores ->
        finish(stores)
        val viewModel = viewModel(stores)

        advanceUntilIdle()

        val record = viewModel.uiState.value.record!!
        assertEquals(Triple(20.minutes, 9.minutes, 75), Triple(record.talkTime, record.userTalk, viewModel.uiState.value.userPercent))
    }

    @Test
    fun `F17_10 다시를 알았음으로 바꾸면 판정 전 복습 값에서 알았음으로 다시 계산하고 연습 기록의 판정도 바꾼다`() = test { stores ->
        val (before, review) = judged(stores, Rating.Again)
        finish(stores, listOf(review))
        val viewModel = viewModel(stores)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.ToggleVerdict(review.cardId))
        advanceUntilIdle()

        val expected = before.review(Rating.Good, judgedAt, ReviewAmount.Normal)
        val saved = stores.speaking.sessions().first().single().oralReviews.single()
        assertEquals(expected to true, stores.cards.card(review.cardId) to saved.correct)
    }

    @Test
    fun `F17_10 알았음을 다시로 바꾸면 다시 누른 횟수가 하나 오른다`() = test { stores ->
        val (before, review) = judged(stores, Rating.Good)
        finish(stores, listOf(review))
        val viewModel = viewModel(stores)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.ToggleVerdict(review.cardId))
        advanceUntilIdle()

        assertEquals(before.againCount + 1, stores.cards.card(review.cardId)!!.againCount)
    }

    @Test
    fun `F17_10 판정 뒤에 다시 복습한 카드는 판정을 바꿀 수 없다`() = test { stores ->
        val (_, review) = judged(stores, Rating.Again)
        stores.cards.rate(review.cardId, Rating.Good, judgedAt + 1.days, ReviewAmount.Normal)
        val later = stores.cards.card(review.cardId)
        finish(stores, listOf(review))
        val viewModel = viewModel(stores)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.ToggleVerdict(review.cardId))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.oral.single().locked)
        assertEquals(later to false, stores.cards.card(review.cardId) to stores.speaking.sessions().first().single().oralReviews.single().correct)
    }

    @Test
    fun `F17_7 길게 눌러 신고하면 카드를 빼고 연습 기록의 신고 수를 하나 올린다`() = test { stores ->
        finish(stores)
        val id = stores.cards.save(card())
        val viewModel = viewModel(stores)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.Report(id))
        advanceUntilIdle()

        assertEquals(null to 1, stores.cards.card(id) to stores.speaking.sessions().first().single().reportCount)
    }

    @Test
    fun `F17_7 밀어서 뺀 카드는 5초 안에 되돌리면 다시 생긴다`() = test { stores ->
        finish(stores)
        val id = stores.cards.save(card())
        val viewModel = viewModel(stores)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.Remove(id))
        advanceTimeBy(4.seconds)
        viewModel.onIntent(SpeakingSummaryIntent.Undo)
        advanceUntilIdle()

        assertEquals(listOf(id), viewModel.uiState.value.cards.map { it.id })
    }

    @Test
    fun `F17_7 되돌리기는 5초 뒤에 사라진다`() = test { stores ->
        finish(stores)
        val id = stores.cards.save(card())
        val viewModel = viewModel(stores)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.Remove(id))
        advanceTimeBy(5.seconds + 1.seconds)

        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `F17_6 정리에 실패한 연습은 다시 시도로 정리를 다시 넣는다`() = test { stores ->
        finish(stores)
        val retried = mutableListOf<String>()
        val viewModel = viewModel(stores, retried)
        advanceUntilIdle()

        viewModel.onIntent(SpeakingSummaryIntent.Retry)
        advanceUntilIdle()

        assertEquals(listOf("p1"), retried)
    }
}
