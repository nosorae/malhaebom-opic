package io.github.nosorae.aiopic.ui.featurelive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [selected] 는 지금 목표 등급이고 읽기 전에는 null 이다. */
data class CoachStartUiState(val grades: List<String> = emptyList(), val selected: String? = null)

sealed interface CoachStartIntent {
    data class SelectTargetGrade(val grade: String) : CoachStartIntent
}

/**
 * 코치 시작 시트(F10.2~F10.5). 전역 목표 등급을 구독해서 보이고, 고르면 전역 값을 바꾼다. 고른 값이 다음 세션의 기본값이 된다.
 * 시작과 닫기는 단순 이동이라 화면이 람다로 처리한다.
 */
@Inject
class CoachStartViewModel(observeTargetGradeUseCase: ObserveTargetGradeUseCase, private val setTargetGradeUseCase: SetTargetGradeUseCase) : ViewModel() {
    private val _uiState = MutableStateFlow(CoachStartUiState())
    val uiState: StateFlow<CoachStartUiState> = _uiState.asStateFlow()

    init {
        observeTargetGradeUseCase()
            .onEach { choice -> _uiState.update { it.copy(grades = choice.grades, selected = choice.selected) } }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: CoachStartIntent) {
        when (intent) {
            is CoachStartIntent.SelectTargetGrade -> viewModelScope.launch { setTargetGradeUseCase(intent.grade) }
        }
    }
}
