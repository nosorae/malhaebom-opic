package io.github.nosorae.aiopic.ui.featuresettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.ui.featurelive.ObserveTargetGradeUseCase
import io.github.nosorae.aiopic.ui.featurelive.SetTargetGradeUseCase
import io.github.nosorae.aiopic.ui.featuresettings.consent.RevokeVoiceConsentUseCase
import io.github.nosorae.aiopic.ui.featuresettings.haptics.SetHapticsUseCase
import io.github.nosorae.aiopic.ui.featuresettings.key.DeleteKeyUseCase
import io.github.nosorae.aiopic.ui.featuresettings.model.SelectModelUseCase
import io.github.nosorae.aiopic.ui.featuresettings.practice.ImportQuestionsUseCase
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionImportResult
import io.github.nosorae.aiopic.ui.featuresettings.practice.SetAnswerTimerUseCase
import io.github.nosorae.aiopic.ui.featuresettings.theme.SetThemeModeUseCase
import io.github.nosorae.aiopic.ui.featuresettings.voice.SetQuestionVoiceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 설정 탭(S14)의 분석 모델, 제공사별 키, 음성 전송 동의, 답변 타이머, 문항 파일, 진동, 질문 음성, 코치 목표 등급, 화면 모드. */
@Inject
class SettingsViewModel(
    private val loadSettingsUseCase: LoadSettingsUseCase,
    private val selectModelUseCase: SelectModelUseCase,
    private val deleteKeyUseCase: DeleteKeyUseCase,
    private val setThemeModeUseCase: SetThemeModeUseCase,
    private val revokeVoiceConsentUseCase: RevokeVoiceConsentUseCase,
    private val setAnswerTimerUseCase: SetAnswerTimerUseCase,
    private val importQuestionsUseCase: ImportQuestionsUseCase,
    private val setHapticsUseCase: SetHapticsUseCase,
    private val setQuestionVoiceUseCase: SetQuestionVoiceUseCase,
    observeTargetGradeUseCase: ObserveTargetGradeUseCase,
    private val setTargetGradeUseCase: SetTargetGradeUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        load()
        // 시작 시트에서도 바뀌는 전역 값이라 한 번 읽지 않고 구독한다(F10.3).
        observeTargetGradeUseCase()
            .onEach { choice -> _uiState.update { it.copy(targetGrades = choice.grades, targetGrade = choice.selected) } }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Reload -> load()

            is SettingsIntent.SelectModel -> viewModelScope.launch {
                selectModelUseCase(intent.id)
                _uiState.update { it.copy(selectedModelId = intent.id, fellBackFrom = null) }
            }

            is SettingsIntent.SelectThemeMode -> viewModelScope.launch {
                setThemeModeUseCase(intent.mode)
                _uiState.update { it.copy(themeMode = intent.mode) }
            }

            is SettingsIntent.RequestKeyDeletion -> _uiState.update { it.copy(deletingKey = intent.provider) }

            SettingsIntent.CancelKeyDeletion -> _uiState.update { it.copy(deletingKey = null) }

            SettingsIntent.DeleteKey -> deleteKey()

            SettingsIntent.RequestConsentRevoke -> _uiState.update { it.copy(revokingConsent = true) }

            SettingsIntent.CancelConsentRevoke -> _uiState.update { it.copy(revokingConsent = false) }

            SettingsIntent.RevokeConsent -> {
                _uiState.update { it.copy(revokingConsent = false) }
                viewModelScope.launch {
                    revokeVoiceConsentUseCase()
                    _uiState.update { it.copy(consentGiven = false) }
                }
            }

            is SettingsIntent.SetAnswerTimer -> viewModelScope.launch {
                setAnswerTimerUseCase(intent.on)
                _uiState.update { it.copy(answerTimerOn = intent.on) }
            }

            is SettingsIntent.SetHaptics -> viewModelScope.launch {
                setHapticsUseCase(intent.on)
                _uiState.update { it.copy(hapticsOn = intent.on) }
            }

            is SettingsIntent.SetNaturalVoice -> viewModelScope.launch {
                setQuestionVoiceUseCase(intent.on)
                _uiState.update { it.copy(naturalVoiceOn = intent.on) }
            }

            is SettingsIntent.SelectTargetGrade -> viewModelScope.launch { setTargetGradeUseCase(intent.grade) }

            is SettingsIntent.ImportQuestions -> viewModelScope.launch {
                val result = importQuestionsUseCase(intent.json)
                _uiState.update {
                    it.copy(
                        questionImport = result,
                        importedQuestionCount = (result as? QuestionImportResult.Imported)?.count ?: it.importedQuestionCount,
                    )
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val loaded = loadSettingsUseCase()
            _uiState.update {
                it.copy(
                    models = loaded.models,
                    selectedModelId = loaded.selectedModelId,
                    // 다시 읽을 때는 이미 기본 모델이 저장돼 있어 null 이 온다. 사용자가 다른 모델을 고르기 전까지 알림을 남긴다.
                    fellBackFrom = loaded.fellBackFrom ?: it.fellBackFrom,
                    keys = loaded.keys,
                    themeMode = loaded.themeMode,
                    consentGiven = loaded.consentGiven,
                    answerTimerOn = loaded.answerTimerOn,
                    hapticsOn = loaded.hapticsOn,
                    naturalVoiceOn = loaded.naturalVoiceOn,
                    highQualityDeviceVoice = loaded.highQualityDeviceVoice,
                    importedQuestionCount = loaded.importedQuestionCount,
                )
            }
        }
    }

    private fun deleteKey() {
        val provider = _uiState.value.deletingKey ?: return
        _uiState.update { it.copy(deletingKey = null) }
        viewModelScope.launch {
            deleteKeyUseCase(provider)
            _uiState.update { state ->
                state.copy(keys = state.keys.map { if (it.provider == provider) it.copy(maskedKey = null) else it })
            }
        }
    }
}
