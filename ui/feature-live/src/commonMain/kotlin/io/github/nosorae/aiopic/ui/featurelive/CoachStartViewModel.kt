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

/**
 * [selected] 는 지금 목표 등급이고 읽기 전에는 null 이다. [starting] 은 시작을 눌러 질문을 고르는 중이다. 이동을 처리할 때까지 다시 시작하지 않는다.
 */
data class CoachStartUiState(
    val grades: List<String> = emptyList(),
    val selected: String? = null,
    val starting: Boolean = false,
    val navigation: CoachStartDestination? = null,
)

/** 질문을 골라야 가는 이동(ADR 0009). */
sealed interface CoachStartDestination {
    /** 새 세션으로 대화 화면을 연다. */
    data class Start(val question: CoachQuestion, val targetGrade: String) : CoachStartDestination

    /** 낼 질문이 없어 시트만 닫는다. */
    data object Close : CoachStartDestination
}

sealed interface CoachStartIntent {
    data class SelectTargetGrade(val grade: String) : CoachStartIntent

    /** 질문을 골라 새 세션을 시작한다. 질문은 한 문항 연습과 같이 가장 약한 유형에서 고른다(F4.1). */
    data object Start : CoachStartIntent

    data object NavigationHandled : CoachStartIntent
}

/**
 * 코치 시작 시트(F10.2~F10.5). 전역 목표 등급을 구독해서 보이고, 고르면 전역 값을 바꾼다. 고른 값이 다음 세션의 기본값이 된다.
 * 시작은 질문을 골라야 가는 이동이라 상태로 하고, 닫기는 단순 이동이라 화면이 람다로 처리한다.
 */
@Inject
class CoachStartViewModel(
    observeTargetGradeUseCase: ObserveTargetGradeUseCase,
    private val setTargetGradeUseCase: SetTargetGradeUseCase,
    private val pickCoachQuestionUseCase: PickCoachQuestionUseCase,
) : ViewModel() {
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
            CoachStartIntent.Start -> start()
            CoachStartIntent.NavigationHandled -> _uiState.update { it.copy(starting = false, navigation = null) }
        }
    }

    private fun start() {
        val state = _uiState.value
        val grade = state.selected ?: return
        if (state.starting) return
        _uiState.update { it.copy(starting = true) }
        viewModelScope.launch {
            val question = pickCoachQuestionUseCase()
            _uiState.update { it.copy(navigation = question?.let { q -> CoachStartDestination.Start(q, grade) } ?: CoachStartDestination.Close) }
        }
    }
}
