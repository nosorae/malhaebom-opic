package io.github.nosorae.aiopic.ui.featurelive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.CoachStatus
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.LiveCoach
import io.github.nosorae.aiopic.engine.coach.MaterialKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
    /** 코치 목소리가 나오는 중이면 코치 쪽 띠를, 아니면 내 쪽 띠를 보인다(F12.1). */
    val coachSpeaking: Boolean get() = playbackLevel > 0f
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

    /** 앱이 앞으로 왔다. 앱이 뒤로 가며 멈춘 것만 이어 간다. */
    data object ReturnToApp : LiveCoachIntent

    data class EditMaterial(val kind: MaterialKind, val text: String) : LiveCoachIntent

    /** [index] 번째 표현 카드의 영어 표현을 고친다. 한 말과 등급은 코치가 정한 그대로 둔다. */
    data class EditExpression(val index: Int, val english: String) : LiveCoachIntent
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
    observeCoachLevelsUseCase: ObserveCoachLevelsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LiveCoachUiState(question = question))
    val uiState: StateFlow<LiveCoachUiState> = _uiState.asStateFlow()

    private var opening: Job? = null
    private var coach: LiveCoach? = null

    /** 앱이 뒤로 가며 멈췄다. 사용자가 멈춘 것은 앱이 돌아와도 이어 가지 않는다. */
    private var pausedByApp = false

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

            LiveCoachIntent.NextPhase -> run { it.nextPhase() }

            LiveCoachIntent.Pause -> run { it.pause() }

            LiveCoachIntent.Resume -> run { it.resume() }

            LiveCoachIntent.LeaveApp -> run {
                if (it.state.value?.status == CoachStatus.Active) {
                    pausedByApp = true
                    it.pause()
                }
            }

            LiveCoachIntent.ReturnToApp -> run {
                if (pausedByApp) {
                    pausedByApp = false
                    it.resume()
                }
            }

            is LiveCoachIntent.EditMaterial -> run { it.editMaterial(intent.kind, intent.text) }

            is LiveCoachIntent.EditExpression -> run { coach ->
                coach.state.value?.expressions?.getOrNull(intent.index)?.let { coach.editExpression(intent.index, it.copy(english = intent.english.trim())) }
            }
        }
    }

    private fun connect() {
        if (_uiState.value.consented != true || opening != null) return
        _uiState.update { it.copy(micDenied = false) }
        opening = viewModelScope.launch {
            val opened = openCoachUseCase(sessionId, questionId, question, targetGrade, viewModelScope)
            coach = opened
            opened.state.filterNotNull().onEach { state -> _uiState.update { it.copy(coach = state) } }.launchIn(viewModelScope)
        }
    }

    private fun run(action: suspend (LiveCoach) -> Unit) {
        val current = coach ?: return
        viewModelScope.launch { action(current) }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(sessionId: String, questionId: String, question: String, targetGrade: String): LiveCoachViewModel
    }
}
