package io.github.nosorae.aiopic.ui.featuresettings

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.Stability
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyProvider
import io.github.nosorae.aiopic.ui.featuresettings.key.KeyStatus
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionImportResult
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode

data class SettingsUiState(
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    /** 고른 모델이 목록에서 빠져 기본 모델로 돌렸을 때 빠진 모델 id. 모델을 다시 고르면 지운다. */
    val fellBackFrom: String? = null,
    val keys: List<KeyStatus> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.System,
    /** 지우기 확인을 묻는 중인 제공사. */
    val deletingKey: KeyProvider? = null,
    /** 음성 전송 동의(F1.3). 철회하면 false 이고, 동의 화면에서 다시 동의하고 돌아오면 다시 읽는다. */
    val consentGiven: Boolean = true,
    /** 철회 확인을 묻는 중이다. */
    val revokingConsent: Boolean = false,
    /** 문항별 답변 타이머(F2.7). 기본은 꺼짐이다. */
    val answerTimerOn: Boolean = false,
    /** 진동. 기본은 켬이다. */
    val hapticsOn: Boolean = true,
    /** 질문을 자연스러운 음성으로 읽는다(#161). false 면 기기 음성이다. */
    val naturalVoiceOn: Boolean = true,
    /** 영어(미국) 고품질 기기 음성이 설치돼 있다. */
    val highQualityDeviceVoice: Boolean = true,
    /** 불러온 문항 파일의 문항 수(F6.3). 없으면 0 이다. */
    val importedQuestionCount: Int = 0,
    /** 방금 불러온 결과 안내. 다시 불러오기 전까지 남는다. */
    val questionImport: QuestionImportResult? = null,
    /** 코치 목표 등급으로 고를 수 있는 등급과 지금 값(F10.3). 읽기 전에는 비어 있다. */
    val targetGrades: List<String> = emptyList(),
    val targetGrade: String? = null,
) {
    val selectedModel: ModelInfo? get() = models.firstOrNull { it.id == selectedModelId }

    /** 미리보기 모델은 2주 공지로 내려갈 수 있어서 골라 둔 동안 안내한다(F5.8). */
    val showsPreviewNotice: Boolean get() = selectedModel?.stability == Stability.Preview

    /** 기기 음성을 골랐는데 고품질 음성이 없으면 받는 곳을 한 줄로 안내한다. */
    val showsDeviceVoiceGuide: Boolean get() = !naturalVoiceOn && !highQualityDeviceVoice
}

sealed interface SettingsIntent {
    /** 키 입력 화면에서 돌아왔을 때 키 저장 여부를 다시 읽는다. */
    data object Reload : SettingsIntent

    data class SelectModel(val id: String) : SettingsIntent

    data class SelectThemeMode(val mode: ThemeMode) : SettingsIntent

    data class RequestKeyDeletion(val provider: KeyProvider) : SettingsIntent

    data object DeleteKey : SettingsIntent

    data object CancelKeyDeletion : SettingsIntent

    data object RequestConsentRevoke : SettingsIntent

    data object RevokeConsent : SettingsIntent

    data object CancelConsentRevoke : SettingsIntent

    data class SetAnswerTimer(val on: Boolean) : SettingsIntent

    data class SetHaptics(val on: Boolean) : SettingsIntent

    /** 질문 음성을 고른다. false 면 기기 음성이다. */
    data class SetNaturalVoice(val on: Boolean) : SettingsIntent

    data class SelectTargetGrade(val grade: String) : SettingsIntent

    /** 화면이 고른 파일의 내용을 넘긴다. 파일을 읽지 못했으면 null 이다. 고르기를 취소하면 보내지 않는다. */
    data class ImportQuestions(val json: String?) : SettingsIntent
}
