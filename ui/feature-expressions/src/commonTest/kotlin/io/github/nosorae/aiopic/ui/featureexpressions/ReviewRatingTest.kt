package io.github.nosorae.aiopic.ui.featureexpressions

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.ReviewAmount
import io.github.nosorae.aiopic.engine.review.localDay
import io.github.nosorae.aiopic.engine.review.nextDayStart
import io.github.nosorae.aiopic.engine.review.review
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 복습 화면과 표현 상세의 평가가 카드 줄과 복습한 날에 쓰이는지(F18.3, F18.6, F18.8). 실제 카드 저장소(메모리 DB)를 쓴다. */
class ReviewRatingTest {
    private val dispatcher = StandardTestDispatcher()
    private val seoul = TimeZone.of("Asia/Seoul")

    // 서울 18시. 앱의 하루는 새벽 4시에 바뀐다(F18.11).
    private val now = Instant.parse("2026-11-19T09:00:00Z")
    private val clock = FixedClock(now)
    private val settings = FakeReviewSettings()
    private val voice = FakeVoice()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(CardStore) -> Unit) = runTest(dispatcher) {
        val db = inMemoryDatabase(dispatcher)
        try {
            block(CardStore(db, seoul))
        } finally {
            db.close()
        }
    }

    private fun review(store: CardStore) = ReviewViewModel(
        startReviewUseCase = StartReviewUseCase(store, settings, clock, seoul),
        rateCardUseCase = RateCardUseCase(store, settings, clock),
        observeExpressionsUseCase = ObserveExpressionsUseCase(store, clock, seoul),
        speakExpressionUseCase = SpeakExpressionUseCase(voice),
        setSilentUseCase = SetSilentUseCase(settings),
    )

    private fun detail(store: CardStore, id: Long) = ExpressionDetailViewModel(
        cardId = id,
        openExpressionUseCase = OpenExpressionUseCase(store, seoul),
        editExpressionUseCase = EditExpressionUseCase(store),
        knowExpressionUseCase = KnowExpressionUseCase(store, settings, clock),
        archiveExpressionUseCase = ArchiveExpressionUseCase(store),
        deleteExpressionUseCase = DeleteExpressionUseCase(store),
        speakExpressionUseCase = SpeakExpressionUseCase(voice),
    )

    @Test
    fun `F18_6 알았음을 누르면 다음 복습 시각과 복습 횟수가 바뀌고 오늘이 복습한 날에 들어간다`() = test { store ->
        val id = store.save(card(now - 2.days))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.Reveal)
        viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        advanceUntilIdle()

        val rated = card(now - 2.days).copy(id = id).review(Rating.Good, now, ReviewAmount.Normal)
        assertEquals(rated to setOf(now.localDay(seoul)), store.card(id) to store.reviewDays().first())
    }

    @Test
    fun `F18_6 다시를 누르면 다시 누른 횟수도 오른다`() = test { store ->
        val id = store.save(card(now - 2.days))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.Reveal)
        viewModel.onIntent(ReviewIntent.Rate(Rating.Again))
        advanceUntilIdle()

        assertEquals(1, store.card(id)?.againCount)
    }

    @Test
    fun `F18_6 처음 봄 카드의 다음은 평가 없이 다음 날 새벽 4시로 옮기고 복습 횟수는 그대로 둔다`() = test { store ->
        val id = store.save(card(now - 1.hours, firstSeen = true))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.Next)
        advanceUntilIdle()

        val expected = card(now - 1.hours).copy(id = id, firstSeen = false, lastReviewedAt = now, dueAt = now.nextDayStart(seoul))
        assertEquals(expected to setOf(now.localDay(seoul)), store.card(id) to store.reviewDays().first())
    }

    @Test
    fun `F18_4 처음 봄 카드는 열리자마자 더 나은 표현을 읽는다`() = test { store ->
        store.save(card(now - 1.hours, firstSeen = true))

        review(store)
        advanceUntilIdle()

        assertEquals(listOf("I went to school yesterday."), voice.spoken)
    }

    @Test
    fun `F18_5 소리 없이를 켜면 정답을 열어도 읽지 않는다`() = test { store ->
        store.save(card(now - 2.days))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.SetSilent(true))
        viewModel.onIntent(ReviewIntent.Reveal)
        advanceUntilIdle()

        assertEquals(emptyList(), voice.spoken)
    }

    @Test
    fun `F18_3 이미 알아요는 카드를 빼지 않고 쉬움으로 평가해 간격만 늘린다`() = test { store ->
        val id = store.save(card(now - 2.days))
        val viewModel = detail(store, id)
        advanceUntilIdle()

        viewModel.onIntent(ExpressionDetailIntent.Know)
        advanceUntilIdle()

        val known = card(now - 2.days).copy(id = id).review(Rating.Easy, now, ReviewAmount.Normal)
        assertEquals(listOf(known), store.cards().first())
    }

    @Test
    fun `F18_8 묶음 중간에 나갔다 다시 열면 남은 카드부터 이어 간다`() = test { store ->
        val first = store.save(card(now - 3.days))
        val second = store.save(card(now - 2.days, kind = CardKind.Formulaic))
        val viewModel = review(store)
        advanceUntilIdle()
        val rated = viewModel.uiState.value.current?.id

        viewModel.onIntent(ReviewIntent.Reveal)
        viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        advanceUntilIdle()
        val reopened = review(store)
        advanceUntilIdle()

        assertEquals(setOf(first, second) - setOfNotNull(rated), setOfNotNull(reopened.uiState.value.current?.id))
    }

    @Test
    fun `F18_8 묶음 중간 카드를 지우고 나머지를 평가한 뒤 다시 열면 평가한 카드가 다시 나오지 않는다`() = test { store ->
        listOf(3, 2, 1).forEach { store.save(card(now - it.days)) }
        val order = review(store).also { advanceUntilIdle() }.uiState.value.remaining.map { it.id }
        store.delete(order[0])

        val shown = List(3) {
            val viewModel = review(store)
            advanceUntilIdle()
            val current = viewModel.uiState.value.current?.id
            viewModel.onIntent(ReviewIntent.Reveal)
            viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
            advanceUntilIdle()
            current
        }

        assertEquals(listOf(order[1], order[2], null), shown)
    }

    @Test
    fun `F18_3 묶음을 연 뒤 보관한 카드는 다시 열면 나오지 않는다`() = test { store ->
        listOf(3, 2).forEach { store.save(card(now - it.days)) }
        val order = review(store).also { advanceUntilIdle() }.uiState.value.remaining.map { it.id }
        store.archive(order[0], true)

        val reopened = review(store)
        advanceUntilIdle()

        assertEquals(listOf(order[1]), reopened.uiState.value.remaining.map { it.id })
    }

    @Test
    fun `F18_8 어제 만든 묶음은 이어 가지 않고 오늘 카드로 새 묶음을 연다`() = test { store ->
        val id = store.save(card(now - 2.days))
        settings.batch = ReviewBatch(day = now.localDay(seoul) - 1, cardIds = listOf(id), results = listOf(id to Rating.Good))

        val viewModel = review(store)
        advanceUntilIdle()

        assertEquals(listOf(id) to emptyList(), viewModel.uiState.value.remaining.map { it.id } to viewModel.uiState.value.results)
    }

    @Test
    fun `F18_4 한 묶음은 앞 6장이고 끝낸 뒤 6장 더를 누르면 남은 카드로 새 묶음을 연다`() = test { store ->
        (1..7).forEach { store.save(card(now - it.days)) }
        val viewModel = review(store)
        advanceUntilIdle()
        val first = viewModel.uiState.value.total

        repeat(first) {
            viewModel.onIntent(ReviewIntent.Reveal)
            viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        }
        advanceUntilIdle()
        val canDoMore = viewModel.uiState.value.canDoMore
        viewModel.onIntent(ReviewIntent.MoreCards)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf<Any>(6, true, 1, 0), listOf(first, canDoMore, state.remaining.size, state.results.size))
    }

    @Test
    fun `F18_8 오늘 남은 카드가 없으면 6장 더를 보이지 않는다`() = test { store ->
        store.save(card(now - 2.days))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.Reveal)
        viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.canDoMore)
    }

    @Test
    fun `F18_6 알았음을 빠르게 두 번 눌러도 다음 카드는 정답을 열기 전이라 평가하지 않는다`() = test { store ->
        listOf(3, 2).forEach { store.save(card(now - it.days)) }
        val viewModel = review(store)
        advanceUntilIdle()
        val second = viewModel.uiState.value.remaining[1].id

        viewModel.onIntent(ReviewIntent.Reveal)
        viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        viewModel.onIntent(ReviewIntent.Next)
        advanceUntilIdle()

        assertEquals(second to 0, viewModel.uiState.value.current?.id to store.card(second)?.reviewCount)
    }

    @Test
    fun `F18_5 소리 없이를 켜 두면 처음 봄 카드도 읽지 않는다`() = test { store ->
        settings.silent = true
        store.save(card(now - 1.hours, firstSeen = true))

        review(store)
        advanceUntilIdle()

        assertEquals(emptyList(), voice.spoken)
    }

    @Test
    fun `F18_8 이번 주 처음 복습한 날 묶음을 끝내면 축하한다`() = test { store ->
        store.save(card(now - 2.days))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.Reveal)
        viewModel.onIntent(ReviewIntent.Rate(Rating.Good))
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.celebrate)
    }

    @Test
    fun `F18_8 묶음을 끝내면 알았음과 다시와 처음 본 장 수를 센다`() = test { store ->
        store.save(card(now - 3.days, firstSeen = true))
        store.save(card(now - 2.days))
        store.save(card(now - 1.days))
        val viewModel = review(store)
        advanceUntilIdle()

        viewModel.onIntent(ReviewIntent.Next)
        listOf(Rating.Again, Rating.Easy).forEach {
            viewModel.onIntent(ReviewIntent.Reveal)
            viewModel.onIntent(ReviewIntent.Rate(it))
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(1, 1, 1, 1), listOf(state.knewCount, state.againCount, state.firstSeenCount, state.weekDays))
    }
}
