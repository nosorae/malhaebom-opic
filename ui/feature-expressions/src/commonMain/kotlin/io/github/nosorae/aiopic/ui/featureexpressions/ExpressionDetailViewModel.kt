package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 표현 상세(S20). [editing] 이면 [draftBetter], [draftMeaning] 을 고치는 중이다. [intervalExtended] 는 이미 알아요를 누른 뒤 안내를 보일지다.
 * [deleted] 는 지운 뒤 표현 탭으로 돌아갈 일회성 이동이다.
 */
data class ExpressionDetailUiState(
    val loaded: Boolean = false,
    val detail: ExpressionDetail? = null,
    val editing: Boolean = false,
    val draftBetter: String = "",
    val draftMeaning: String = "",
    val confirmingDelete: Boolean = false,
    val intervalExtended: Boolean = false,
    val deleted: Boolean = false,
) {
    /** 보관한 카드에서는 이미 알아요를 숨긴다(F18.3). */
    val canKnow: Boolean get() = detail?.card?.archived == false

    val canSave: Boolean get() = draftBetter.isNotBlank() && draftMeaning.isNotBlank()
}

sealed interface ExpressionDetailIntent {
    data object Listen : ExpressionDetailIntent

    data object StartEdit : ExpressionDetailIntent

    data class ChangeBetter(val text: String) : ExpressionDetailIntent

    data class ChangeMeaning(val text: String) : ExpressionDetailIntent

    data object SaveEdit : ExpressionDetailIntent

    data object CancelEdit : ExpressionDetailIntent

    data object Know : ExpressionDetailIntent

    data object ToggleArchive : ExpressionDetailIntent

    data object AskDelete : ExpressionDetailIntent

    data object CancelDelete : ExpressionDetailIntent

    data object ConfirmDelete : ExpressionDetailIntent

    data object NavigationHandled : ExpressionDetailIntent
}

/** 표현 상세. [cardId] 는 화면 인자다. 열 때 처음 봄을 끄고(F18.3) 카드가 바뀌면 따라 그린다. */
@AssistedInject
class ExpressionDetailViewModel(
    @Assisted private val cardId: Long,
    openExpressionUseCase: OpenExpressionUseCase,
    private val editExpressionUseCase: EditExpressionUseCase,
    private val knowExpressionUseCase: KnowExpressionUseCase,
    private val archiveExpressionUseCase: ArchiveExpressionUseCase,
    private val deleteExpressionUseCase: DeleteExpressionUseCase,
    private val speakExpressionUseCase: SpeakExpressionUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExpressionDetailUiState())
    val uiState: StateFlow<ExpressionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            openExpressionUseCase(cardId).collect { detail -> _uiState.update { it.copy(loaded = true, detail = detail) } }
        }
    }

    fun onIntent(intent: ExpressionDetailIntent) {
        val card = _uiState.value.detail?.card
        when (intent) {
            ExpressionDetailIntent.Listen -> card?.let { viewModelScope.launch { speakExpressionUseCase(it.better) } }

            ExpressionDetailIntent.StartEdit -> card?.let { c -> _uiState.update { it.copy(editing = true, draftBetter = c.better, draftMeaning = c.meaning) } }

            is ExpressionDetailIntent.ChangeBetter -> _uiState.update { it.copy(draftBetter = intent.text) }

            is ExpressionDetailIntent.ChangeMeaning -> _uiState.update { it.copy(draftMeaning = intent.text) }

            ExpressionDetailIntent.SaveEdit -> {
                val state = _uiState.value
                if (!state.canSave) return
                _uiState.update { it.copy(editing = false) }
                viewModelScope.launch { editExpressionUseCase(cardId, state.draftBetter, state.draftMeaning) }
            }

            ExpressionDetailIntent.CancelEdit -> _uiState.update { it.copy(editing = false) }

            ExpressionDetailIntent.Know -> {
                _uiState.update { it.copy(intervalExtended = true) }
                viewModelScope.launch { knowExpressionUseCase(cardId) }
            }

            ExpressionDetailIntent.ToggleArchive -> card?.let { viewModelScope.launch { archiveExpressionUseCase(cardId, !it.archived) } }

            ExpressionDetailIntent.AskDelete -> _uiState.update { it.copy(confirmingDelete = true) }

            ExpressionDetailIntent.CancelDelete -> _uiState.update { it.copy(confirmingDelete = false) }

            ExpressionDetailIntent.ConfirmDelete -> {
                _uiState.update { it.copy(confirmingDelete = false) }
                viewModelScope.launch {
                    deleteExpressionUseCase(cardId)
                    _uiState.update { it.copy(deleted = true) }
                }
            }

            ExpressionDetailIntent.NavigationHandled -> _uiState.update { it.copy(deleted = false) }
        }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(cardId: Long): ExpressionDetailViewModel
    }
}
