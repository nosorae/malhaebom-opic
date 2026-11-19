package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.speaking.SpeakingRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** 연습 정리 화면(S18, F17.6). [record] 가 null 이고 [loaded] 면 지운 연습이다. [notice] 는 아래에 5초 보이는 안내다. */
data class SpeakingSummaryUiState(
    val loaded: Boolean = false,
    val record: SpeakingRecord? = null,
    val cards: List<ExpressionCard> = emptyList(),
    val oral: List<OralRow> = emptyList(),
    val notice: SummaryNotice? = null,
) {
    /** 내 말 비율(F16.24), 퍼센트. */
    val userPercent: Int get() = ((record?.userShare ?: 0.0) * 100).roundToInt()
}

/** 카드를 뺀 뒤 5초 보이는 안내(F17.7). 밀어서 뺀 카드만 되돌릴 수 있다. */
sealed interface SummaryNotice {
    data class Removed(val card: ExpressionCard) : SummaryNotice

    data object Reported : SummaryNotice
}

sealed interface SpeakingSummaryIntent {
    /** 말로 복습 판정 바꾸기(F17.10). */
    data class ToggleVerdict(val cardId: Long) : SpeakingSummaryIntent

    /** 왼쪽으로 밀어서 빼기(F17.7). */
    data class Remove(val cardId: Long) : SpeakingSummaryIntent

    data object Undo : SpeakingSummaryIntent

    /** 길게 눌러 틀린 지적으로 신고하기(F17.7). */
    data class Report(val cardId: Long) : SpeakingSummaryIntent

    data object Retry : SpeakingSummaryIntent
}

/** 되돌리기를 보이는 시간(F17.7). */
private val NOTICE_TIME = 5.seconds

/** 카드 열기와 "다 봤어요" 는 단순 이동이라 화면이 람다로 처리한다. */
@AssistedInject
class SpeakingSummaryViewModel(
    @Assisted private val practiceId: String,
    observeSpeakingSummaryUseCase: ObserveSpeakingSummaryUseCase,
    private val changeJudgmentUseCase: ChangeJudgmentUseCase,
    private val removeCardUseCase: RemoveCardUseCase,
    private val reportCardUseCase: ReportCardUseCase,
    private val retryCardsUseCase: RetryCardsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpeakingSummaryUiState())
    val uiState: StateFlow<SpeakingSummaryUiState> = _uiState.asStateFlow()
    private var noticeJob: Job? = null

    init {
        viewModelScope.launch {
            observeSpeakingSummaryUseCase(practiceId).collect { summary ->
                _uiState.update { it.copy(loaded = true, record = summary.record, cards = summary.cards, oral = summary.oral) }
            }
        }
    }

    fun onIntent(intent: SpeakingSummaryIntent) {
        when (intent) {
            is SpeakingSummaryIntent.ToggleVerdict -> viewModelScope.launch { changeJudgmentUseCase(practiceId, intent.cardId) }

            is SpeakingSummaryIntent.Remove -> {
                val card = _uiState.value.cards.firstOrNull { it.id == intent.cardId } ?: return
                show(SummaryNotice.Removed(card))
                viewModelScope.launch { removeCardUseCase(card, removed = true) }
            }

            SpeakingSummaryIntent.Undo -> {
                val removed = _uiState.value.notice as? SummaryNotice.Removed ?: return
                noticeJob?.cancel()
                _uiState.update { it.copy(notice = null) }
                viewModelScope.launch { removeCardUseCase(removed.card, removed = false) }
            }

            is SpeakingSummaryIntent.Report -> {
                show(SummaryNotice.Reported)
                viewModelScope.launch { reportCardUseCase(practiceId, intent.cardId) }
            }

            SpeakingSummaryIntent.Retry -> viewModelScope.launch { retryCardsUseCase(practiceId) }
        }
    }

    private fun show(notice: SummaryNotice) {
        noticeJob?.cancel()
        _uiState.update { it.copy(notice = notice) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_TIME)
            _uiState.update { it.copy(notice = null) }
        }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(practiceId: String): SpeakingSummaryViewModel
    }
}
