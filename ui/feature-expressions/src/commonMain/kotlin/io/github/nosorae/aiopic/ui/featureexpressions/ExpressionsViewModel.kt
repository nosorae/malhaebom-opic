package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.minutesLeft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 묶음 칩(F18.1). 처음은 전체다. 보관함 말고는 보관하지 않은 카드만 보인다. */
enum class ExpressionFilter(val kind: CardKind?) {
    All(null),
    Correction(CardKind.Correction),
    UnknownExpression(CardKind.UnknownExpression),
    Formulaic(CardKind.Formulaic),
    MoreNatural(CardKind.MoreNatural),
    Archived(null),
    ;

    fun matches(card: ExpressionCard): Boolean = if (this == Archived) card.archived else !card.archived && (kind == null || card.kind == kind)
}

/** 표현 탭(S19). [cards] 는 보관한 카드까지 모든 카드다. */
data class ExpressionsUiState(
    val loaded: Boolean = false,
    val cards: List<ExpressionCard> = emptyList(),
    val todayCount: Int = 0,
    val weekDays: Int = 0,
    val filter: ExpressionFilter = ExpressionFilter.All,
) {
    val minutesLeft: Int get() = minutesLeft(todayCount)

    /** 자주 틀리는 유형. 보관하지 않은 고칠 표현만 틀린 유형마다 세어 많은 순으로 둔다. */
    val errorCounts: List<Pair<ErrorType, Int>>
        get() = cards.filter { !it.archived && it.kind == CardKind.Correction }.mapNotNull { it.errorType }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

    val shown: List<ExpressionCard> get() = cards.filter(filter::matches)
}

sealed interface ExpressionsIntent {
    data class SelectFilter(val filter: ExpressionFilter) : ExpressionsIntent
}

/** 카드 열기, 복습 시작은 단순 이동이라 화면이 람다로 처리한다. */
@Inject
class ExpressionsViewModel(observeExpressionsUseCase: ObserveExpressionsUseCase) : ViewModel() {
    private val _uiState = MutableStateFlow(ExpressionsUiState())
    val uiState: StateFlow<ExpressionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeExpressionsUseCase().collect { overview ->
                _uiState.update { it.copy(loaded = true, cards = overview.cards, todayCount = overview.todayCount, weekDays = overview.weekDays) }
            }
        }
    }

    fun onIntent(intent: ExpressionsIntent) {
        when (intent) {
            is ExpressionsIntent.SelectFilter -> _uiState.update { it.copy(filter = intent.filter) }
        }
    }
}
