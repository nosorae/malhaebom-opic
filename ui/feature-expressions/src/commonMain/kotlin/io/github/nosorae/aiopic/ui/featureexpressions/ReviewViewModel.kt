package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.Rating
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 복습(S21). [remaining] 의 맨 앞이 지금 카드이고 [results] 는 이번 묶음에서 누른 결과다. [allRatings] 는 길게 눌러 어려움과 쉬움까지 보이는지다.
 * [todayCount], [weekDays] 는 복습 끝에 보이는 오늘 남은 장 수와 이번 주 복습한 날 수다.
 */
data class ReviewUiState(
    val loaded: Boolean = false,
    val remaining: List<ExpressionCard> = emptyList(),
    val results: List<Rating?> = emptyList(),
    val revealed: Boolean = false,
    val allRatings: Boolean = false,
    val silent: Boolean = false,
    val todayCount: Int = 0,
    val weekDays: Int = 0,
) {
    val current: ExpressionCard? get() = remaining.firstOrNull()
    val total: Int get() = results.size + remaining.size
    val finished: Boolean get() = loaded && current == null
    val firstSeenCount: Int get() = results.count { it == null }
    val againCount: Int get() = results.count { it == Rating.Again }
    val knewCount: Int get() = results.size - firstSeenCount - againCount

    /** 오늘 복습할 카드가 남았으면 6장 더를 보인다(F18.8). */
    val canDoMore: Boolean get() = todayCount > 0
}

sealed interface ReviewIntent {
    /** 정답 보기. */
    data object Reveal : ReviewIntent

    data class Rate(val rating: Rating) : ReviewIntent

    /** 처음 봄 카드의 다음. */
    data object Next : ReviewIntent

    /** 평가 버튼을 길게 눌러 어려움과 쉬움까지 보인다. */
    data object ShowAllRatings : ReviewIntent

    data class SetSilent(val on: Boolean) : ReviewIntent

    /** 6장 더. */
    data object MoreCards : ReviewIntent
}

/**
 * 복습 화면. 누를 때마다 카드 줄에 바로 쓰고(F18.8) 화면은 다음 카드로 넘어간다. 쓰기는 누른 순서대로 하나씩 한다.
 * 처음 봄 카드가 나오면 더 나은 표현을 읽고, 정답을 열면 읽는다. 소리 없이면 읽지 않는다(F18.4, F18.5).
 */
@Inject
class ReviewViewModel(
    private val startReviewUseCase: StartReviewUseCase,
    private val rateCardUseCase: RateCardUseCase,
    observeExpressionsUseCase: ObserveExpressionsUseCase,
    private val speakExpressionUseCase: SpeakExpressionUseCase,
    private val setSilentUseCase: SetSilentUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()
    private val writes = Mutex()

    init {
        start(more = false)
        viewModelScope.launch {
            observeExpressionsUseCase().collect { overview -> _uiState.update { it.copy(todayCount = overview.todayCount, weekDays = overview.weekDays) } }
        }
    }

    fun onIntent(intent: ReviewIntent) {
        when (intent) {
            ReviewIntent.Reveal -> {
                _uiState.update { it.copy(revealed = true) }
                speakCurrent()
            }
            is ReviewIntent.Rate -> advance(intent.rating)
            ReviewIntent.Next -> advance(null)
            ReviewIntent.ShowAllRatings -> _uiState.update { it.copy(allRatings = true) }
            is ReviewIntent.SetSilent -> {
                _uiState.update { it.copy(silent = intent.on) }
                viewModelScope.launch { setSilentUseCase(intent.on) }
            }
            ReviewIntent.MoreCards -> start(more = true)
        }
    }

    private fun start(more: Boolean) {
        viewModelScope.launch {
            val session = writes.withLock { startReviewUseCase(more) }
            _uiState.update { it.copy(loaded = true, remaining = session.remaining, results = session.results, revealed = false, allRatings = false, silent = session.silent) }
            speakIfFirstSeen()
        }
    }

    private fun advance(rating: Rating?) {
        val card = _uiState.value.current ?: return
        _uiState.update { it.copy(remaining = it.remaining.drop(1), results = it.results + rating, revealed = false, allRatings = false) }
        viewModelScope.launch { writes.withLock { rateCardUseCase(card.id, rating) } }
        speakIfFirstSeen()
    }

    private fun speakIfFirstSeen() {
        if (_uiState.value.current?.firstSeen == true) speakCurrent()
    }

    private fun speakCurrent() {
        val state = _uiState.value
        if (state.silent) return
        state.current?.let { viewModelScope.launch { speakExpressionUseCase(it.better) } }
    }
}
