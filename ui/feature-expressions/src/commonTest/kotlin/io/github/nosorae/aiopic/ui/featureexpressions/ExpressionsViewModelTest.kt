package io.github.nosorae.aiopic.ui.featureexpressions

import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ErrorType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 표현 탭과 표현 상세(F18.1, F18.3). */
class ExpressionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val seoul = TimeZone.of("Asia/Seoul")
    private val now = Instant.parse("2026-11-19T09:00:00Z")
    private val clock = FixedClock(now)
    private val settings = FakeReviewSettings()

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

    private fun tab(store: CardStore) = ExpressionsViewModel(ObserveExpressionsUseCase(store, clock, seoul))

    private fun detail(store: CardStore, id: Long) = ExpressionDetailViewModel(
        cardId = id,
        openExpressionUseCase = OpenExpressionUseCase(store, seoul),
        editExpressionUseCase = EditExpressionUseCase(store),
        knowExpressionUseCase = KnowExpressionUseCase(store, settings, clock),
        archiveExpressionUseCase = ArchiveExpressionUseCase(store),
        deleteExpressionUseCase = DeleteExpressionUseCase(store),
        speakExpressionUseCase = SpeakExpressionUseCase(FakeVoice()),
    )

    @Test
    fun `F18_1 자주 틀리는 유형은 보관하지 않은 고칠 표현만 많은 순으로 센다`() = test { store ->
        store.save(card(now - 1.days, errorType = ErrorType.Article))
        store.save(card(now - 2.days, errorType = ErrorType.Tense))
        store.save(card(now - 3.days, errorType = ErrorType.Tense))
        store.save(card(now - 4.days, errorType = ErrorType.Article, archived = true))
        store.save(card(now - 5.days, kind = CardKind.Formulaic))
        val viewModel = tab(store)

        advanceUntilIdle()

        assertEquals(listOf(ErrorType.Tense to 2, ErrorType.Article to 1), viewModel.uiState.value.errorCounts)
    }

    @Test
    fun `F18_1 보관함 칩은 보관한 카드만 보이고 전체는 보관한 카드를 뺀다`() = test { store ->
        val kept = store.save(card(now - 1.days))
        val archived = store.save(card(now - 2.days, archived = true))
        val viewModel = tab(store)
        advanceUntilIdle()
        val all = viewModel.uiState.value.shown.map { it.id }

        viewModel.onIntent(ExpressionsIntent.SelectFilter(ExpressionFilter.Archived))

        assertEquals(listOf(kept) to listOf(archived), all to viewModel.uiState.value.shown.map { it.id })
    }

    @Test
    fun `F18_1 오늘 복습은 남은 장 수와 분을 보인다`() = test { store ->
        repeat(4) { store.save(card(now - (it + 1).days)) }
        val viewModel = tab(store)

        advanceUntilIdle()

        assertEquals(4 to 2, viewModel.uiState.value.let { it.todayCount to it.minutesLeft })
    }

    @Test
    fun `F18_3 상세에 들어오면 처음 봄이 꺼지고 복습 횟수는 그대로다`() = test { store ->
        val id = store.save(card(now - 1.hours, firstSeen = true))

        detail(store, id)
        advanceUntilIdle()

        assertEquals(false to 0, store.card(id)!!.let { it.firstSeen to it.reviewCount })
    }

    @Test
    fun `F18_3 필요 없어요는 보관하고 이미 알아요를 숨긴다`() = test { store ->
        val id = store.save(card(now - 1.days))
        val viewModel = detail(store, id)
        advanceUntilIdle()

        viewModel.onIntent(ExpressionDetailIntent.ToggleArchive)
        advanceUntilIdle()

        assertTrue(store.card(id)!!.archived)
        assertFalse(viewModel.uiState.value.canKnow)
    }

    @Test
    fun `F18_3 고치기로 더 나은 표현과 뜻을 바꾼다`() = test { store ->
        val id = store.save(card(now - 1.days))
        val viewModel = detail(store, id)
        advanceUntilIdle()

        viewModel.onIntent(ExpressionDetailIntent.StartEdit)
        viewModel.onIntent(ExpressionDetailIntent.ChangeBetter("I went to school."))
        viewModel.onIntent(ExpressionDetailIntent.ChangeMeaning("학교에 갔다."))
        viewModel.onIntent(ExpressionDetailIntent.SaveEdit)
        advanceUntilIdle()

        assertEquals("I went to school." to "학교에 갔다.", store.card(id)!!.let { it.better to it.meaning })
    }

    @Test
    fun `F18_3 삭제를 확인하면 카드가 지워지고 표현 탭으로 간다`() = test { store ->
        val id = store.save(card(now - 1.days))
        val viewModel = detail(store, id)
        advanceUntilIdle()

        viewModel.onIntent(ExpressionDetailIntent.AskDelete)
        viewModel.onIntent(ExpressionDetailIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(emptyList<Long>() to true, store.cards().first().map { it.id } to viewModel.uiState.value.deleted)
    }
}
