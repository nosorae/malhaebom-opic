package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 연습 중 화면(F16.18). [practice] 는 앱 범위에서 도는 연습이고 화면은 지켜보기만 한다(ADR 0038). */
data class SpeakingUiState(
    val practice: SpeakingState? = null,
    val level: Float = 0f,
    val navigation: SpeakingDestination? = null,
)

sealed interface SpeakingDestination {
    /** 연습이 끝나 그 연습의 정리 화면으로 바꾼다. */
    data class Summary(val practiceId: String) : SpeakingDestination

    /** 도는 연습이 없다. 프로세스가 끝난 뒤 되살린 백스택에서 열렸을 때다. */
    data object Exit : SpeakingDestination
}

sealed interface SpeakingIntent {
    data class Send(val action: PracticeAction) : SpeakingIntent

    data object NavigationHandled : SpeakingIntent
}

@Inject
class SpeakingViewModel(observePracticeUseCase: ObservePracticeUseCase, private val controlPracticeUseCase: ControlPracticeUseCase) : ViewModel() {
    private val _uiState = MutableStateFlow(SpeakingUiState())
    val uiState: StateFlow<SpeakingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            var seen = false
            observePracticeUseCase().collect { (practice, level) ->
                // 이동은 연습 상태가 바뀔 때만 넣는다. 목소리 크기만 바뀐 값으로 다시 넣으면 처리한 이동이 되살아난다.
                val changed = !seen || practice != _uiState.value.practice
                seen = true
                val navigation = when {
                    !changed -> null
                    practice == null -> SpeakingDestination.Exit
                    practice.finished -> SpeakingDestination.Summary(practice.id)
                    else -> null
                }
                _uiState.update { it.copy(practice = practice, level = level, navigation = navigation ?: it.navigation) }
            }
        }
    }

    fun onIntent(intent: SpeakingIntent) {
        when (intent) {
            is SpeakingIntent.Send -> viewModelScope.launch { controlPracticeUseCase(intent.action) }
            SpeakingIntent.NavigationHandled -> _uiState.update { it.copy(navigation = null) }
        }
    }
}
