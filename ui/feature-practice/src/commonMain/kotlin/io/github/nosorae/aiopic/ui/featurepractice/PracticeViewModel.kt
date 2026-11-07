package io.github.nosorae.aiopic.ui.featurepractice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.nosorae.aiopic.libs.timer.api.MonotonicTimer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 한 문항 연습 화면(S12). 약한 유형에서 질문 하나를 골라 모의고사와 같은 규칙으로 답하게 한다(F4.1).
 * Next 를 누르면 답변을 저장하고 그 답변의 리포트로 간다(F4.2). [answerId] 는 화면 인자로, 연습 탭이 들어올 때마다 새로 만든다.
 *
 * 다시 듣기와 Next 의 5초, 10초는 단조 시계 [timer] 로 잰다. 40분 타이머는 없다.
 * 스크립트 화면에서 "이 스크립트로 연습" 으로 들어오면 [questionId] 질문을 내고 [script] 를 녹음 화면에 보인다(F14.5).
 */
// ponytail: 연습 중 앱이 꺼지면 녹음을 버리고 처음부터 다시 한다. 질문 하나라 이어 보기를 두지 않았다.
@AssistedInject
class PracticeViewModel(
    @Assisted private val answerId: String,
    @Assisted private val questionId: String?,
    @Assisted script: String?,
    private val pickPracticeQuestionUseCase: PickPracticeQuestionUseCase,
    private val playPracticeQuestionUseCase: PlayPracticeQuestionUseCase,
    private val submitPracticeAnswerUseCase: SubmitPracticeAnswerUseCase,
    private val stopRecordingUseCase: StopRecordingUseCase,
    private val observeVoiceLevelUseCase: ObserveVoiceLevelUseCase,
    private val timer: MonotonicTimer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PracticeUiState(script = script))
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    /** 지금 녹음이 시작된 단조 시계 값. 녹음 중이 아니면 null 이다. */
    private var recordingStartedAt: Duration? = null
    private var playing: Job? = null
    private var clock: Job? = null

    /** 녹음하는 동안만 마이크 음량을 모은다. */
    private var listening: Job? = null

    /** 이 답변 녹음에서 들은 가장 큰 음량. 연습은 앱이 꺼지면 녹음을 버려서 이 화면이 늘 처음부터 듣는다(#241). */
    private var loudest = 0f

    init {
        viewModelScope.launch {
            val pick = pickPracticeQuestionUseCase(questionId)
            _uiState.update { it.copy(question = pick?.question, weakness = pick?.weakness, error = noQuestion(pick)) }
        }
    }

    private fun noQuestion(pick: PracticePick?) = when {
        pick != null -> null
        questionId != null -> PracticeError.ScriptQuestionMissing
        else -> PracticeError.QuestionUnavailable
    }

    fun onIntent(intent: PracticeIntent) {
        if (_uiState.value.saving && intent != PracticeIntent.NavigationHandled) return
        when (intent) {
            PracticeIntent.PlayQuestion -> if (_uiState.value.canPlay) play(stopFirst = false)
            PracticeIntent.ReplayQuestion -> replay()
            PracticeIntent.SubmitAnswer -> submit()
            PracticeIntent.MicDenied -> _uiState.update { it.copy(phase = PracticePhase.Ready, error = PracticeError.MicDenied) }
            PracticeIntent.ExitPractice -> exit()
            PracticeIntent.NavigationHandled -> _uiState.update { it.copy(navigation = null) }
        }
    }

    private fun play(stopFirst: Boolean) {
        val question = _uiState.value.question ?: return
        recordingEnded()
        _uiState.update { it.copy(phase = PracticePhase.Speaking, error = null) }
        playing = viewModelScope.launch {
            val error = (if (stopFirst) stopRecordingUseCase() else null) ?: playPracticeQuestionUseCase(question, answerId)
            if (error != null) {
                _uiState.update { it.copy(phase = PracticePhase.Ready, error = error) }
                return@launch
            }
            val startedAt = timer.elapsed()
            recordingStartedAt = startedAt
            _uiState.update { it.copy(phase = PracticePhase.Recording, recorded = Duration.ZERO) }
            listening = launch {
                observeVoiceLevelUseCase().collect { level ->
                    loudest = loudest.coerceAtLeast(level)
                    _uiState.update { it.copy(voiceLevel = level) }
                }
            }
            clock = launch {
                while (true) {
                    delay(TICK)
                    _uiState.update { it.copy(recorded = timer.elapsed() - startedAt) }
                }
            }
        }
    }

    /** 녹음 시작 5초 안에 한 번만 된다. 녹음을 멈추고 질문을 다시 들은 뒤 같은 폴더에 이어 녹음한다. */
    private fun replay() {
        val startedAt = recordingStartedAt ?: return
        if (_uiState.value.replayUsed || timer.elapsed() - startedAt >= ReplayWindow) return
        _uiState.update { it.copy(replayUsed = true) }
        play(stopFirst = true)
    }

    /** Next 는 녹음 시작 10초 뒤에만 된다. */
    private fun submit() {
        val startedAt = recordingStartedAt ?: return
        val question = _uiState.value.question ?: return
        if (timer.elapsed() - startedAt < NextDelay) return
        recordingEnded()
        save(submitting = true) {
            when (val error = submitPracticeAnswerUseCase(question, answerId, loudest)) {
                // ponytail: 다시 녹음은 남은 조각 뒤에 이어 쓴다. 앞 답변이 겹쳐 들리는 게 문제가 되면 녹음 폴더를 비우고 시작한다.
                null -> _uiState.update { it.copy(navigation = PracticeDestination.Feedback(answerId)) }

                else -> _uiState.update { it.copy(phase = PracticePhase.Ready, error = error) }
            }
        }
    }

    private fun exit() {
        playing?.cancel()
        val recording = recordingStartedAt != null
        recordingEnded()
        save {
            if (recording) stopRecordingUseCase()
            _uiState.update { it.copy(navigation = PracticeDestination.Exit) }
        }
    }

    /** 녹음 시각, 시계, 음량 모으기를 멈춘다. 목소리 띠는 다음 녹음까지 가라앉는다. */
    private fun recordingEnded() {
        recordingStartedAt = null
        clock?.cancel()
        listening?.cancel()
        _uiState.update { it.copy(voiceLevel = 0f) }
    }

    /** 사용자가 누른 저장을 하는 동안 [PracticeUiState.saving] 을 켜 둔다. */
    private fun save(submitting: Boolean = false, block: suspend () -> Unit) {
        _uiState.update { it.copy(saving = true, submitting = submitting) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _uiState.update { it.copy(saving = false, submitting = false) }
            }
        }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(answerId: String, questionId: String?, script: String?): PracticeViewModel
    }

    private companion object {
        val TICK = 1.seconds
    }
}
