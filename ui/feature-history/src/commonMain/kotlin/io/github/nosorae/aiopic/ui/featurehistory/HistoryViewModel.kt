package io.github.nosorae.aiopic.ui.featurehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    /** 스크립트, 회차와 연습 답변. 스크립트를 맨 위에 두고 나머지는 최근 것부터다. */
    val entries: List<HistoryEntry> = emptyList(),
    val weaknesses: List<HistoryWeakness> = emptyList(),
    val grades: List<String> = emptyList(),
) {
    val sessions: List<HistorySession> get() = entries.filterIsInstance<HistorySession>()

    val empty: Boolean get() = !loading && entries.isEmpty() && weaknesses.isEmpty()
}

sealed interface HistoryIntent {
    /** 오른 약점 줄이 화면에 그려져 축하를 시작했다(#198). 그 점수를 본 것으로 남겨 다시 열거나 앱을 다시 켜도 다시 터지지 않게 한다. */
    data class MarkWeaknessSeen(val weakness: HistoryWeakness) : HistoryIntent
}

/** 기록 탭(S13). 저장소 값을 그대로 보여 주는 화면이라 stateIn 으로 내보낸다. 회차 열기는 화면이 람다로 처리한다. */
@Inject
class HistoryViewModel(observeHistoryUseCase: ObserveHistoryUseCase, private val markWeaknessSeenUseCase: MarkWeaknessSeenUseCase) : ViewModel() {
    val uiState: StateFlow<HistoryUiState> = observeHistoryUseCase()
        .map { HistoryUiState(loading = false, entries = it.entries, weaknesses = it.weaknesses, grades = it.grades) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onIntent(intent: HistoryIntent) {
        when (intent) {
            is HistoryIntent.MarkWeaknessSeen -> viewModelScope.launch { markWeaknessSeenUseCase(intent.weakness) }
        }
    }
}
