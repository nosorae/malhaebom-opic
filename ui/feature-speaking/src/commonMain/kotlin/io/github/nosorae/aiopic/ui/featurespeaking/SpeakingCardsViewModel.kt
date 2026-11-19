package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.minutesLeft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** 연습 탭 맨 위 카드 둘(F16.1, F18.10). 읽기 전에는 [loaded] 가 false 라 그리지 않는다. */
data class SpeakingCardsUiState(
    val loaded: Boolean = false,
    val defaultMinutes: Int = 0,
    val lastPractice: LocalDate? = null,
    val running: Boolean = false,
    val todayCount: Int = 0,
    val weekDays: Int = 0,
    val hasCards: Boolean = false,
) {
    val minutesLeft: Int get() = minutesLeft(todayCount)
}

/** 시작 시트 열기, 연습 중 화면과 복습 화면 열기는 단순 이동이라 화면이 람다로 처리한다. */
@Inject
class SpeakingCardsViewModel(observeSpeakingCardsUseCase: ObserveSpeakingCardsUseCase) : ViewModel() {
    private val _uiState = MutableStateFlow(SpeakingCardsUiState())
    val uiState: StateFlow<SpeakingCardsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeSpeakingCardsUseCase().collect { cards ->
                _uiState.update { SpeakingCardsUiState(true, cards.defaultMinutes, cards.lastPractice, cards.running, cards.todayCount, cards.weekDays, cards.hasCards) }
            }
        }
    }
}
