package io.github.nosorae.aiopic.ui.featuresettings

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.answer.check.choose
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.port.ModelCatalog
import io.github.nosorae.aiopic.engine.question.pick.parseQuestions
import io.github.nosorae.aiopic.libs.keyvault.api.KeyVault
import io.github.nosorae.aiopic.ui.featureonboarding.consent.VoiceConsent
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyProvider
import io.github.nosorae.aiopic.ui.featuresettings.haptics.HapticsStore
import io.github.nosorae.aiopic.ui.featuresettings.key.KeyStatus
import io.github.nosorae.aiopic.ui.featuresettings.key.maskKey
import io.github.nosorae.aiopic.ui.featuresettings.model.ModelSelectionStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.AnswerTimerStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionFileStore
import io.github.nosorae.aiopic.ui.featuresettings.speaking.SpeakingPrefs
import io.github.nosorae.aiopic.ui.featuresettings.speaking.SpeakingPrefsStore
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeModeStore
import io.github.nosorae.aiopic.ui.featuresettings.voice.QuestionVoiceStore

data class LoadedSettings(
    val models: List<ModelInfo>,
    val selectedModelId: String,
    val fellBackFrom: String?,
    val keys: List<KeyStatus>,
    val themeMode: ThemeMode,
    val consentGiven: Boolean,
    val answerTimerOn: Boolean,
    val hapticsOn: Boolean,
    val naturalVoiceOn: Boolean,
    val highQualityDeviceVoice: Boolean,
    val importedQuestionCount: Int,
    val speaking: SpeakingPrefs,
)

/**
 * 설정 화면에 보일 값을 읽는다. 고른 모델이 앱 업데이트로 목록에서 빠졌으면 기본 모델을 저장해 두고 [LoadedSettings.fellBackFrom] 으로
 * 알린다(F5.8). 분석 중에 모델이 내려가 앱이 기본 모델로 돌린 경우도 같이 알린다. 기본 모델을 저장하므로 알림은 한 번만 나온다.
 */
// ponytail: 첫 버전은 제공사가 Gemini 하나라 모델 목록도 하나만 받는다. 제공사를 더하면 제공사마다 목록을 받는다.
@Inject
class LoadSettingsUseCase(
    private val modelCatalog: ModelCatalog,
    private val modelSelectionStore: ModelSelectionStore,
    private val themeModeStore: ThemeModeStore,
    private val keyVault: KeyVault,
    private val voiceConsent: VoiceConsent,
    private val answerTimerStore: AnswerTimerStore,
    private val questionFileStore: QuestionFileStore,
    private val hapticsStore: HapticsStore,
    private val questionVoiceStore: QuestionVoiceStore,
    private val speakingPrefsStore: SpeakingPrefsStore,
) {
    suspend operator fun invoke(): LoadedSettings {
        val choice = modelCatalog.choose(modelSelectionStore.selectedModelId())
        if (choice.fellBackFrom != null) modelSelectionStore.selectModel(choice.model.id)
        return LoadedSettings(
            models = modelCatalog.models,
            selectedModelId = choice.model.id,
            fellBackFrom = choice.fellBackFrom ?: modelSelectionStore.takeFellBackFrom(),
            keys = KeyProvider.entries.map { KeyStatus(it, maskedKey = keyVault.get(it.id)?.let(::maskKey)) },
            themeMode = themeModeStore.themeMode.value,
            consentGiven = voiceConsent.isGiven(),
            answerTimerOn = answerTimerStore.answerTimerOn(),
            hapticsOn = hapticsStore.hapticsOn(),
            naturalVoiceOn = questionVoiceStore.naturalVoiceOn(),
            highQualityDeviceVoice = questionVoiceStore.hasHighQualityDeviceVoice(),
            // 저장할 때 검사를 마친 파일이라 다시 읽어도 통과한다.
            importedQuestionCount = questionFileStore.questionFile()?.let { parseQuestions(it).size } ?: 0,
            speaking = speakingPrefsStore.speakingPrefs(),
        )
    }
}
