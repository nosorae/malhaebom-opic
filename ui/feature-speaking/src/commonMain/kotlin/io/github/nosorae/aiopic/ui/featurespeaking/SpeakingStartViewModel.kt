package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 연습 시작 시트(F16.2, F16.3). [minutes] 는 읽기 전에 null 이다. [needsMic] 이면 화면이 마이크 권한을 받아 결과를 넘긴다.
 * [starting] 은 시작을 눌러 연습을 여는 중이고 이동을 처리할 때까지 다시 시작하지 않는다.
 */
data class SpeakingStartUiState(
    val minutes: Int? = null,
    val route: AudioRoute = AudioRoute.Speaker,
    val hasKey: Boolean = true,
    val consented: Boolean = true,
    val needsMic: Boolean = false,
    val micDenied: Boolean = false,
    val starting: Boolean = false,
    val navigation: SpeakingStartDestination? = null,
) {
    val canStart: Boolean get() = minutes != null && hasKey

    /** 고른 시간으로 센 대략의 비용(F16.2). */
    val costUsd: Double get() = estimatedCostUsd(minutes ?: 0)
}

/** 연습이 열려야 가는 이동. */
sealed interface SpeakingStartDestination {
    data object Running : SpeakingStartDestination
}

sealed interface SpeakingStartIntent {
    data class SelectMinutes(val minutes: Int) : SpeakingStartIntent

    data class SelectRoute(val route: AudioRoute) : SpeakingStartIntent

    data object Start : SpeakingStartIntent

    data object MicGranted : SpeakingStartIntent

    data object MicDenied : SpeakingStartIntent

    data object NavigationHandled : SpeakingStartIntent
}

/** 고를 수 있는 연습 시간(F16.2). */
val PRACTICE_MINUTES = listOf(10, 20, 30)

/** [earphones] 는 시트를 열 때 이어폰이 연결돼 있었는지다. 있으면 이어폰, 없으면 스피커가 골라져 있다(F16.2, F20.1). */
@AssistedInject
class SpeakingStartViewModel(
    @Assisted earphones: Boolean,
    loadSpeakingStartUseCase: LoadSpeakingStartUseCase,
    private val startSpeakingUseCase: StartSpeakingUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpeakingStartUiState(route = if (earphones) AudioRoute.Earphones else AudioRoute.Speaker))
    val uiState: StateFlow<SpeakingStartUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val defaults = loadSpeakingStartUseCase()
            _uiState.update { it.copy(minutes = defaults.minutes, hasKey = defaults.hasKey, consented = defaults.consented) }
        }
    }

    fun onIntent(intent: SpeakingStartIntent) {
        when (intent) {
            is SpeakingStartIntent.SelectMinutes -> _uiState.update { it.copy(minutes = intent.minutes) }

            is SpeakingStartIntent.SelectRoute -> _uiState.update { it.copy(route = intent.route) }

            SpeakingStartIntent.Start -> _uiState.update { if (it.canStart && !it.starting) it.copy(starting = true, needsMic = true, micDenied = false) else it }

            SpeakingStartIntent.MicGranted -> {
                val state = _uiState.value
                _uiState.update { it.copy(needsMic = false) }
                viewModelScope.launch {
                    startSpeakingUseCase(state.minutes ?: return@launch, state.route)
                    _uiState.update { it.copy(navigation = SpeakingStartDestination.Running) }
                }
            }

            SpeakingStartIntent.MicDenied -> _uiState.update { it.copy(needsMic = false, micDenied = true, starting = false) }

            SpeakingStartIntent.NavigationHandled -> _uiState.update { it.copy(starting = false, navigation = null) }
        }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(earphones: Boolean): SpeakingStartViewModel
    }
}
