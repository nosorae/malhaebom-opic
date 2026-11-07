package io.github.nosorae.aiopic.ui.featuresettings

import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.Stability
import io.github.nosorae.aiopic.engine.answer.port.ModelCatalog
import io.github.nosorae.aiopic.libs.keyvault.api.KeyVault
import io.github.nosorae.aiopic.ui.featurelive.ObserveTargetGradeUseCase
import io.github.nosorae.aiopic.ui.featurelive.SetTargetGradeUseCase
import io.github.nosorae.aiopic.ui.featurelive.TargetGradeStore
import io.github.nosorae.aiopic.ui.featureonboarding.consent.VoiceConsent
import io.github.nosorae.aiopic.ui.featuresettings.consent.PendingAnalysis
import io.github.nosorae.aiopic.ui.featuresettings.consent.RevokeVoiceConsentUseCase
import io.github.nosorae.aiopic.ui.featuresettings.haptics.HapticsStore
import io.github.nosorae.aiopic.ui.featuresettings.haptics.SetHapticsUseCase
import io.github.nosorae.aiopic.ui.featuresettings.key.DeleteKeyUseCase
import io.github.nosorae.aiopic.ui.featuresettings.model.ModelSelectionStore
import io.github.nosorae.aiopic.ui.featuresettings.model.SelectModelUseCase
import io.github.nosorae.aiopic.ui.featuresettings.practice.AnswerTimerStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.ImportQuestionsUseCase
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionFileStore
import io.github.nosorae.aiopic.ui.featuresettings.practice.SetAnswerTimerUseCase
import io.github.nosorae.aiopic.ui.featuresettings.theme.SetThemeModeUseCase
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeModeStore
import io.github.nosorae.aiopic.ui.featuresettings.voice.QuestionVoiceStore
import io.github.nosorae.aiopic.ui.featuresettings.voice.SetQuestionVoiceUseCase
import kotlinx.coroutines.flow.MutableStateFlow

/** 안정 모델 둘과 미리보기 모델 하나. 실제 Gemini 목록에는 아직 미리보기 모델이 없어 안내를 확인하려고 만든다. */
object FakeCatalog : ModelCatalog {
    val stable = ModelInfo("gemini", "stable-a", "Stable A", hearsAudio = true, stability = Stability.Stable)
    val otherStable = ModelInfo("gemini", "stable-b", "Stable B", hearsAudio = true, stability = Stability.Stable)
    val preview = ModelInfo("gemini", "preview-c", "Preview C", hearsAudio = true, stability = Stability.Preview)

    override val defaultModel = stable
    override val models = listOf(stable, otherStable, preview)
}

class FakeSettingsStore(var modelId: String? = null) :
    ModelSelectionStore,
    ThemeModeStore,
    VoiceConsent,
    AnswerTimerStore,
    QuestionFileStore,
    HapticsStore,
    QuestionVoiceStore,
    TargetGradeStore {
    var consentGiven = true
    var answerTimerOn = false
    var questionFile: String? = null
    var hapticsOn = true
    var naturalVoiceOn = true
    var highQualityDeviceVoice = true

    override val themeMode = MutableStateFlow(ThemeMode.System)

    override val targetGrades = listOf("IM1", "IM2", "IM3", "IH", "AL")
    override val targetGrade = MutableStateFlow("IH")

    override suspend fun setTargetGrade(grade: String) {
        targetGrade.value = grade
    }

    override suspend fun selectedModelId(): String? = modelId

    override suspend fun selectModel(id: String) {
        modelId = id
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }

    override suspend fun isGiven(): Boolean = consentGiven

    override suspend fun give() {
        consentGiven = true
    }

    override suspend fun revoke() {
        consentGiven = false
    }

    override suspend fun answerTimerOn(): Boolean = answerTimerOn

    override suspend fun setAnswerTimerOn(on: Boolean) {
        answerTimerOn = on
    }

    override suspend fun hapticsOn(): Boolean = hapticsOn

    override suspend fun setHapticsOn(on: Boolean) {
        hapticsOn = on
    }

    override suspend fun naturalVoiceOn(): Boolean = naturalVoiceOn

    override suspend fun setNaturalVoiceOn(on: Boolean) {
        naturalVoiceOn = on
    }

    override fun hasHighQualityDeviceVoice(): Boolean = highQualityDeviceVoice

    override suspend fun questionFile(): String? = questionFile

    override suspend fun saveQuestionFile(json: String) {
        questionFile = json
    }
}

/** 남은 분석 작업 id 를 들고 있다가 [cancelAll] 에 비운다. */
class FakePendingAnalysis(vararg pending: String) : PendingAnalysis {
    val pending = mutableListOf(*pending)

    override suspend fun cancelAll() = pending.clear()
}

/** 설정 화면 테스트마다 같은 가짜로 ViewModel 을 만든다. */
fun settingsViewModel(store: FakeSettingsStore, vault: FakeKeyVault, queue: FakePendingAnalysis = FakePendingAnalysis()) = SettingsViewModel(
    loadSettingsUseCase = LoadSettingsUseCase(FakeCatalog, store, store, vault, store, store, store, store, store),
    selectModelUseCase = SelectModelUseCase(store),
    deleteKeyUseCase = DeleteKeyUseCase(vault),
    setThemeModeUseCase = SetThemeModeUseCase(store),
    revokeVoiceConsentUseCase = RevokeVoiceConsentUseCase(store, queue),
    setAnswerTimerUseCase = SetAnswerTimerUseCase(store),
    importQuestionsUseCase = ImportQuestionsUseCase(store),
    setHapticsUseCase = SetHapticsUseCase(store),
    setQuestionVoiceUseCase = SetQuestionVoiceUseCase(store),
    observeTargetGradeUseCase = ObserveTargetGradeUseCase(store),
    setTargetGradeUseCase = SetTargetGradeUseCase(store),
)

class FakeKeyVault(vararg saved: Pair<String, String>) : KeyVault {
    val keys = mutableMapOf(*saved)

    override suspend fun get(provider: String): String? = keys[provider]

    override suspend fun put(provider: String, key: String) {
        keys[provider] = key
    }

    override suspend fun delete(provider: String) {
        keys.remove(provider)
    }
}
