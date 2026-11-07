package io.github.nosorae.aiopic.ui.featurelive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.nosorae.aiopic.engine.coach.CoachEvent
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.CoachStatus
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.MaterialKind
import io.github.nosorae.aiopic.libs.timer.api.MonotonicTimer
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * [consented] 는 실시간 음성 전송 동의 여부이고 읽기 전에는 null 이다. [coach] 는 세션을 열기 전에는 null 이다.
 * 목소리 크기는 마이크와 스피커가 열린 동안만 0 보다 크다.
 */
data class LiveCoachUiState(
    /** 지금 답을 만드는 질문 글. */
    val question: String = "",
    val consented: Boolean? = null,
    val micDenied: Boolean = false,
    val coach: CoachState? = null,
    val micLevel: Float = 0f,
    val playbackLevel: Float = 0f,
    val rehearsal: RehearsalUiState = RehearsalUiState(),
    /** 세션이 끝나 스크립트 화면으로 간다(30분, 피드백 끝, 리허설 없이 넘김). 화면이 옮긴 뒤 [LiveCoachIntent.NavigationHandled] 로 지운다. */
    val navigation: LiveCoachDestination? = null,
) {
    /** 동의는 있는데 세션을 열기 전이고 마이크를 거절하지 않았다. 화면이 마이크 권한을 묻는다. */
    val needsMic: Boolean get() = consented == true && !micDenied && coach == null

    /** 코치 목소리가 나오는 중이면 코치 쪽 띠를, 아니면 내 쪽 띠를 보인다(F12.1). */
    val coachSpeaking: Boolean get() = playbackLevel > 0f
}

/** 저장이 끝나야 가는 이동만 상태로 둔다(ADR 0009). */
sealed interface LiveCoachDestination {
    /** 세션이 끝났다. 스크립트 화면을 연다(F14.1). */
    data object Script : LiveCoachDestination
}

sealed interface LiveCoachIntent {
    data object GiveConsent : LiveCoachIntent

    /** 마이크 권한을 받았다. 저장된 세션이면 이어 가고 없으면 시작한다. */
    data object Connect : LiveCoachIntent

    data object MicDenied : LiveCoachIntent

    data object NextPhase : LiveCoachIntent

    /** "잠깐 생각할게요"(F11.6). */
    data object Pause : LiveCoachIntent

    /** 일시정지에서 이어 가거나 끊긴 연결을 다시 연다(F11.6, F11.9). */
    data object Resume : LiveCoachIntent

    /** 앱이 뒤로 갔다(F11.8). */
    data object LeaveApp : LiveCoachIntent

    /** 앱이 앞으로 왔다. 앱이 뒤로 가며 멈춘 것만 이어 가고, 마이크를 거절했었으면 다시 묻는다. */
    data object ReturnToApp : LiveCoachIntent

    data class EditMaterial(val kind: MaterialKind, val text: String) : LiveCoachIntent

    /** [index] 번째 표현 카드의 영어 표현을 고친다. 한 말과 등급은 코치가 정한 그대로 둔다. */
    data class EditExpression(val index: Int, val english: String) : LiveCoachIntent

    data object NavigationHandled : LiveCoachIntent
}

/**
 * 코치 대화 화면(F11, F12). [sessionId] 세션이 저장돼 있으면 이어 가고 없으면 [questionId] 질문과 [targetGrade] 로 시작한다. [question] 은 화면에 보일 질문 글이다.
 * 동의(F15.1)와 마이크 권한을 받기 전에는 세션을 열지 않는다. 연결은 이 ViewModel 의 범위에서 돌아서 화면을 떠나면 닫히고,
 * 세션은 저장소에 남아 다시 들어오면 이어 간다.
 */
@AssistedInject
class LiveCoachViewModel(
    @Assisted private val sessionId: String,
    @Assisted private val questionId: String,
    @Assisted private val question: String,
    @Assisted private val targetGrade: String,
    private val checkLiveConsentUseCase: CheckLiveConsentUseCase,
    private val giveLiveConsentUseCase: GiveLiveConsentUseCase,
    private val openCoachUseCase: OpenCoachUseCase,
    private val applyCoachEventUseCase: ApplyCoachEventUseCase,
    observeCoachLevelsUseCase: ObserveCoachLevelsUseCase,
    private val startRehearsalUseCase: StartRehearsalUseCase,
    private val stopRehearsalUseCase: StopRehearsalUseCase,
    private val finishRehearsalUseCase: FinishRehearsalUseCase,
    private val transcribeRehearsalUseCase: TranscribeRehearsalUseCase,
    private val observeRehearsalLevelUseCase: ObserveRehearsalLevelUseCase,
    private val timer: MonotonicTimer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LiveCoachUiState(question = question))
    val uiState: StateFlow<LiveCoachUiState> = _uiState.asStateFlow()

    private var opening: Job? = null

    /** 앱이 뒤로 가며 멈췄다. 사용자가 멈춘 것은 앱이 돌아와도 이어 가지 않는다. */
    private var pausedByApp = false

    /** 앱이 멈춘 뒤 [LEAVE_GRACE] 안에 다시 시작하지 않으면 일시정지한다. 구성 변경으로 화면만 다시 만들어지면 취소된다. */
    private var leaving: Job? = null

    /** 리허설 녹음 중에만 음량과 녹음 시간을 모은다. 녹음 중에 화면을 떠나 이 일이 취소되면 녹음을 멈춘다. */
    private var recording: Job? = null

    init {
        viewModelScope.launch {
            val given = checkLiveConsentUseCase()
            _uiState.update { it.copy(consented = given) }
        }
        observeCoachLevelsUseCase().onEach { (mic, playback) -> _uiState.update { it.copy(micLevel = mic, playbackLevel = playback) } }.launchIn(viewModelScope)
    }

    fun onIntent(intent: LiveCoachIntent) {
        when (intent) {
            LiveCoachIntent.GiveConsent -> viewModelScope.launch {
                giveLiveConsentUseCase()
                _uiState.update { it.copy(consented = true) }
            }

            LiveCoachIntent.Connect -> connect()

            LiveCoachIntent.MicDenied -> _uiState.update { it.copy(micDenied = true) }

            LiveCoachIntent.NextPhase -> send(CoachEvent.NextPhase)

            LiveCoachIntent.Pause -> send(CoachEvent.Paused)

            LiveCoachIntent.Resume -> send(CoachEvent.Resumed)

            LiveCoachIntent.LeaveApp -> if (_uiState.value.coach?.status == CoachStatus.Active) {
                leaving = viewModelScope.launch {
                    delay(LEAVE_GRACE)
                    pausedByApp = true
                    applyCoachEventUseCase(sessionId, CoachEvent.Paused)
                }
            }

            LiveCoachIntent.ReturnToApp -> {
                leaving?.cancel()
                // 설정에서 마이크를 켜고 돌아왔을 수 있다. 화면이 다시 묻는다.
                _uiState.update { it.copy(micDenied = false) }
                if (pausedByApp) {
                    pausedByApp = false
                    send(CoachEvent.Resumed)
                }
            }

            is LiveCoachIntent.EditMaterial -> send(CoachEvent.MaterialEdited(intent.kind, intent.text))

            is LiveCoachIntent.EditExpression -> _uiState.value.coach?.expressions?.getOrNull(intent.index)?.let {
                send(CoachEvent.ExpressionEdited(intent.index, it.copy(english = intent.english.trim())))
            }

            LiveCoachIntent.NavigationHandled -> _uiState.update { it.copy(navigation = null) }

            is RehearsalIntent -> rehearse(intent)
        }
    }

    private fun connect() {
        if (_uiState.value.consented != true || opening != null) return
        _uiState.update { it.copy(micDenied = false) }
        opening = viewModelScope.launch {
            var finished = false
            openCoachUseCase(sessionId, questionId, question, targetGrade, viewModelScope).collect { coach ->
                // 끝난 것을 처음 볼 때 한 번만 스크립트로 보낸다. 끝난 세션은 더 바뀌지 않는다.
                val justFinished = !finished && coach.status == CoachStatus.Finished
                finished = finished || justFinished
                _uiState.update { it.copy(coach = coach, navigation = if (justFinished) LiveCoachDestination.Script else it.navigation) }
            }
        }
    }

    private fun rehearse(intent: RehearsalIntent) {
        val step = _uiState.value.rehearsal.step
        when (intent) {
            RehearsalIntent.StartRecording -> if (step == RehearsalStep.Ready) startRecording()

            RehearsalIntent.StopRecording -> if (step == RehearsalStep.Recording) {
                // 단계를 먼저 바꿔 녹음 일이 취소될 때 녹음기를 다시 멈추지 않게 한다.
                setRehearsal { RehearsalUiState(RehearsalStep.Transcribing) }
                recording?.cancel()
                transcribe { finishRehearsalUseCase(sessionId) }
            }

            RehearsalIntent.RetryTranscript -> if (_uiState.value.rehearsal.canRetryTranscript) transcribe { transcribeRehearsalUseCase(sessionId) }

            RehearsalIntent.RehearseAgain -> send(CoachEvent.RehearseAgain)
        }
    }

    private fun startRecording() = viewModelScope.launch {
        setRehearsal { RehearsalUiState(RehearsalStep.Recording) }
        startRehearsalUseCase(sessionId)?.let { error ->
            setRehearsal { RehearsalUiState(error = error) }
            return@launch
        }
        val startedAt = timer.elapsed()
        recording = viewModelScope.launch {
            try {
                launch { observeRehearsalLevelUseCase().collect { level -> setRehearsal { it.copy(voiceLevel = level) } } }
                while (true) {
                    delay(1.seconds)
                    setRehearsal { it.copy(recorded = timer.elapsed() - startedAt) }
                }
            } finally {
                // 녹음 중에 화면을 떠났다. 녹음기는 앱 전체가 함께 써서 여기서 멈춘다.
                if (_uiState.value.rehearsal.step == RehearsalStep.Recording) withContext(NonCancellable) { stopRehearsalUseCase() }
            }
        }
    }

    private fun transcribe(work: suspend () -> RehearsalError?) {
        setRehearsal { RehearsalUiState(RehearsalStep.Transcribing) }
        viewModelScope.launch {
            val error = work()
            setRehearsal { RehearsalUiState(error = error) }
        }
    }

    private fun setRehearsal(change: (RehearsalUiState) -> RehearsalUiState) = _uiState.update { it.copy(rehearsal = change(it.rehearsal)) }

    private fun send(event: CoachEvent) {
        if (_uiState.value.coach == null) return
        viewModelScope.launch { applyCoachEventUseCase(sessionId, event) }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(sessionId: String, questionId: String, question: String, targetGrade: String): LiveCoachViewModel
    }
}

/**
 * 앱이 멈춘 뒤 일시정지까지 기다리는 시간. 화면 회전, 다크 모드, 글자 크기 변경은 Android 가 화면을 다시 만들며 멈췄다 바로 다시 시작해서
 * 그 사이에 연결을 끊지 않는다. 실제로 뒤로 간 앱은 이 시간 뒤에 멈춘다.
 */
// ponytail: 느린 기기에서 화면을 다시 만드는 데 이보다 오래 걸리면 연결이 한 번 끊겼다 이어진다. 그러면 값을 늘린다.
internal val LEAVE_GRACE = 2.seconds
