package io.github.nosorae.aiopic.ui.featurelive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.ScriptLength
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.coach.ScriptResult
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScriptPart { Intro, Body, Closing }

/**
 * [script] 는 만들기 전이나 만들지 못했으면 null 이다. [error] 는 만들기나 다듬기가 실패한 이유이고 다음 시도에서 지운다.
 * [targetGrade] 와 [length] 는 지금 목표 등급과 그 등급의 길이 안내다(F14.2).
 */
data class CoachScriptUiState(
    val question: String = "",
    val script: CoachScript? = null,
    val writing: Boolean = true,
    val polishing: Boolean = false,
    val error: AnalysisError? = null,
    val targetGrade: String = "",
    val length: ScriptLength? = null,
) {
    /** 지금 보이는 세 칸. 고친 판이 있으면 그것이다. */
    val parts: ScriptParts? get() = script?.let { it.edited ?: it.original }

    val edited: Boolean get() = script?.edited != null
}

sealed interface CoachScriptIntent {
    /** 만들기에 실패한 스크립트를 다시 만든다. */
    data object Retry : CoachScriptIntent

    data class Edit(val part: ScriptPart, val text: String) : CoachScriptIntent

    /** 고친 판을 지우고 원본으로 돌린다(F14.3). */
    data object Revert : CoachScriptIntent

    /** 목표 등급에 맞게 다듬는다(F14.4). */
    data object Polish : CoachScriptIntent
}

/**
 * 스크립트 화면(F14). [sessionId] 세션의 스크립트를 열고 없으면 만든다. [question] 은 질문 글이다.
 * 세 칸을 고칠 때마다 고친 판을 바로 저장하고, 화면은 이 ViewModel 이 든 글을 그린다(저장소를 거쳐 돌아오면 입력 중 커서가 튄다).
 */
@AssistedInject
class CoachScriptViewModel(
    @Assisted private val sessionId: String,
    @Assisted private val question: String,
    private val openScriptUseCase: OpenScriptUseCase,
    private val saveScriptEditUseCase: SaveScriptEditUseCase,
    private val polishScriptUseCase: PolishScriptUseCase,
    observeScriptLengthUseCase: ObserveScriptLengthUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CoachScriptUiState(question = question))
    val uiState: StateFlow<CoachScriptUiState> = _uiState.asStateFlow()

    private var saving: Job? = null

    init {
        open()
        observeScriptLengthUseCase().onEach { (grade, length) -> _uiState.update { it.copy(targetGrade = grade, length = length) } }.launchIn(viewModelScope)
    }

    fun onIntent(intent: CoachScriptIntent) {
        val state = _uiState.value
        when (intent) {
            CoachScriptIntent.Retry -> if (!state.writing && state.script == null) open()

            is CoachScriptIntent.Edit -> {
                val parts = state.parts ?: return
                save(
                    when (intent.part) {
                        ScriptPart.Intro -> parts.copy(intro = intent.text)
                        ScriptPart.Body -> parts.copy(body = intent.text)
                        ScriptPart.Closing -> parts.copy(closing = intent.text)
                    },
                )
            }

            CoachScriptIntent.Revert -> save(null)

            CoachScriptIntent.Polish -> polish()
        }
    }

    private fun open() {
        _uiState.update { it.copy(writing = true, error = null) }
        viewModelScope.launch {
            val result = openScriptUseCase(sessionId, question)
            _uiState.update {
                when (result) {
                    is ScriptResult.Success -> it.copy(writing = false, script = result.value)
                    is ScriptResult.Failed -> it.copy(writing = false, error = result.error)
                }
            }
        }
    }

    /** 글은 바로 바꾸고 저장은 마지막 것만 남긴다. 빠르게 치는 동안 앞 저장이 늦게 끝나 새 글을 덮지 않게 한다. */
    private fun save(edited: ScriptParts?) {
        val script = _uiState.value.script ?: return
        if (_uiState.value.polishing) return
        _uiState.update { it.copy(script = script.copy(edited = edited), error = null) }
        saving?.cancel()
        saving = viewModelScope.launch { saveScriptEditUseCase(script, edited) }
    }

    private fun polish() {
        val state = _uiState.value
        val script = state.script ?: return
        if (state.polishing) return
        _uiState.update { it.copy(polishing = true, error = null) }
        viewModelScope.launch {
            saving?.join()
            val result = polishScriptUseCase(_uiState.value.script ?: script, question, state.targetGrade)
            _uiState.update {
                when (result) {
                    is ScriptResult.Success -> it.copy(polishing = false, script = result.value)
                    is ScriptResult.Failed -> it.copy(polishing = false, error = result.error)
                }
            }
        }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(sessionId: String, question: String): CoachScriptViewModel
    }
}
