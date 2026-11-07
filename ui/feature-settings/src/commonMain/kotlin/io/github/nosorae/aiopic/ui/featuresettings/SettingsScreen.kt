package io.github.nosorae.aiopic.ui.featuresettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.Stability
import io.github.nosorae.aiopic.libs.haptics.api.HapticEffect
import io.github.nosorae.aiopic.ui.design.LocalHaptics
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageCheckbox
import io.github.nosorae.aiopic.ui.design.MontageConfirmDialog
import io.github.nosorae.aiopic.ui.design.MontageListCell
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.design.icon_normal_external_link
import io.github.nosorae.aiopic.ui.featurelive.TargetGradeChips
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyProvider
import io.github.nosorae.aiopic.ui.featuresettings.key.KeyStatus
import io.github.nosorae.aiopic.ui.featuresettings.practice.QuestionImportResult
import io.github.nosorae.aiopic.ui.featuresettings.practice.rememberQuestionFilePicker
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import io.github.nosorae.aiopic.ui.design.Res as DesignRes

/**
 * ViewModel 을 관측하는 바깥 겹. 키 입력, 무료 등급 안내(동의 화면), 서베이, 내보내기, 앱 정보는 단순 이동이라 화면을 등록한 쪽이 람다로 연다.
 * 키 입력이나 동의 화면에서 돌아오면 화면이 다시 보일 때마다 키 저장 여부와 동의 상태를 새로 읽는다.
 * 문항 파일 고르기는 플랫폼 창이라 여기서 열고 고른 내용만 ViewModel 에 넘긴다.
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onOpenKeyEntry: (KeyProvider) -> Unit,
    onOpenFreeTierNotice: () -> Unit,
    onOpenFlagExport: () -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSurvey: () -> Unit = {},
    onOpenDeveloperTools: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(SettingsIntent.Reload) }
    val pickQuestionFile = rememberQuestionFilePicker { viewModel.onIntent(SettingsIntent.ImportQuestions(it)) }
    SettingsScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onOpenKeyEntry = onOpenKeyEntry,
        onOpenFreeTierNotice = onOpenFreeTierNotice,
        onOpenFlagExport = onOpenFlagExport,
        onOpenAppInfo = onOpenAppInfo,
        onPickQuestionFile = pickQuestionFile,
        modifier = modifier,
        onOpenSurvey = onOpenSurvey,
        onOpenDeveloperTools = onOpenDeveloperTools,
    )
}

/**
 * 상태만 받아 그리는 안쪽 겹. 미리보기와 스크린샷 테스트가 이 함수를 쓴다.
 * [onOpenDeveloperTools] 는 디버그 빌드에서만 앱이 넘긴다. null 이면 "개발자 도구" 줄이 없다.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onOpenKeyEntry: (KeyProvider) -> Unit,
    onOpenFreeTierNotice: () -> Unit,
    onOpenFlagExport: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onPickQuestionFile: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSurvey: () -> Unit = {},
    onOpenDeveloperTools: (() -> Unit)? = null,
) {
    MontageScreen(modifier = modifier, topBar = { MontageTopBar(title = stringResource(Res.string.settings_title)) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = MontageSpacing.spacing16)) {
            SectionLabel(Res.string.settings_model_section)
            state.fellBackFrom?.let { removed ->
                Notice(stringResource(Res.string.settings_model_fell_back, removed, state.selectedModel?.displayName.orEmpty()))
            }
            state.models.forEach { model ->
                MontageListCell(
                    title = model.displayName,
                    caption = modelCaption(model),
                    selected = model.id == state.selectedModelId,
                    onClick = { onIntent(SettingsIntent.SelectModel(model.id)) },
                )
            }
            if (state.showsPreviewNotice) Notice(stringResource(Res.string.settings_model_preview_notice))
            MontageButton(
                text = stringResource(Res.string.settings_free_tier_link),
                onClick = onOpenFreeTierNotice,
                variant = MontageButtonVariant.Text,
                size = MontageButtonSize.Medium,
                // 글자 버튼의 안쪽 여백에 12dp 를 더해 글자를 다른 줄의 20dp 에 거의 맞춘다.
                modifier = Modifier.padding(horizontal = MontageSpacing.spacing12),
            )

            SectionLabel(Res.string.settings_key_section)
            val uriHandler = LocalUriHandler.current
            state.keys.forEach { key ->
                KeyRow(key, onOpen = { onOpenKeyEntry(key.provider) }, onDelete = { onIntent(SettingsIntent.RequestKeyDeletion(key.provider)) })
            }
            // 키를 새로 받거나 바꿀 때 제공사의 키 발급 페이지를 시스템 브라우저로 연다(F1.1).
            state.keys.forEach { key ->
                MontageButton(
                    text = stringResource(Res.string.settings_key_get, key.provider.label),
                    onClick = { uriHandler.openUri(key.provider.keyPageUrl) },
                    variant = MontageButtonVariant.Text,
                    size = MontageButtonSize.Medium,
                    leadingIcon = DesignRes.drawable.icon_normal_external_link,
                    modifier = Modifier.padding(horizontal = MontageSpacing.spacing12),
                )
            }

            SectionLabel(Res.string.settings_consent_section)
            ConsentRow(
                given = state.consentGiven,
                onRevoke = { onIntent(SettingsIntent.RequestConsentRevoke) },
                onOpenConsent = onOpenFreeTierNotice,
            )

            SectionLabel(Res.string.settings_practice_section)
            MontageListCell(
                title = stringResource(Res.string.settings_survey),
                caption = stringResource(Res.string.settings_survey_caption),
                chevron = true,
                onClick = onOpenSurvey,
                modifier = Modifier.testTag("settings-survey"),
            )
            MontageListCell(
                title = stringResource(Res.string.settings_answer_timer),
                caption = stringResource(Res.string.settings_answer_timer_caption),
                onClick = { onIntent(SettingsIntent.SetAnswerTimer(!state.answerTimerOn)) },
            ) {
                MontageCheckbox(checked = state.answerTimerOn, onCheckedChange = { onIntent(SettingsIntent.SetAnswerTimer(it)) })
            }
            val haptics = LocalHaptics.current
            val setHaptics = { on: Boolean ->
                onIntent(SettingsIntent.SetHaptics(on))
                // 켜는 즉시 저장돼 앱 틀의 켜기/끄기를 통과하므로, 켰을 때만 느낌을 한 번 보여 준다.
                if (on) haptics.play(HapticEffect.Selection)
            }
            MontageListCell(
                title = stringResource(Res.string.settings_haptics),
                caption = stringResource(Res.string.settings_haptics_caption),
                onClick = { setHaptics(!state.hapticsOn) },
            ) {
                MontageCheckbox(checked = state.hapticsOn, onCheckedChange = setHaptics)
            }
            MontageListCell(
                title = stringResource(Res.string.settings_question_file),
                caption = if (state.importedQuestionCount > 0) {
                    stringResource(Res.string.settings_question_file_count, state.importedQuestionCount)
                } else {
                    stringResource(Res.string.settings_question_file_empty)
                },
                chevron = true,
                onClick = onPickQuestionFile,
            )
            state.questionImport?.let { QuestionImportNotice(it) }

            SectionLabel(Res.string.settings_target_grade_section)
            TargetGradeChips(
                grades = state.targetGrades,
                selected = state.targetGrade,
                onSelect = { onIntent(SettingsIntent.SelectTargetGrade(it)) },
                modifier = Modifier.padding(horizontal = MontageSpacing.spacing20),
            )
            Notice(stringResource(Res.string.settings_target_grade_caption))

            SectionLabel(Res.string.settings_voice_section)
            MontageListCell(
                title = stringResource(Res.string.settings_voice_natural),
                caption = stringResource(Res.string.settings_voice_natural_caption),
                selected = state.naturalVoiceOn,
                onClick = { onIntent(SettingsIntent.SetNaturalVoice(on = true)) },
                modifier = Modifier.testTag("settings-voice-natural"),
            )
            MontageListCell(
                title = stringResource(Res.string.settings_voice_device),
                caption = stringResource(Res.string.settings_voice_device_caption),
                selected = !state.naturalVoiceOn,
                onClick = { onIntent(SettingsIntent.SetNaturalVoice(on = false)) },
                modifier = Modifier.testTag("settings-voice-device"),
            )
            if (state.showsDeviceVoiceGuide) Notice(stringResource(Res.string.settings_voice_device_guide))

            SectionLabel(Res.string.settings_theme_section)
            ThemeMode.entries.forEach { mode ->
                MontageListCell(
                    title = stringResource(themeLabel(mode)),
                    selected = mode == state.themeMode,
                    onClick = { onIntent(SettingsIntent.SelectThemeMode(mode)) },
                )
            }

            MontageListCell(
                title = stringResource(Res.string.settings_privacy_policy),
                // 화면 모드 선택지로 읽히지 않게 묶음 사이 간격만큼 띄운다.
                modifier = Modifier.padding(top = MontageSpacing.spacing24),
                chevron = true,
                onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
            )
            // 리포트에서 "틀림"을 누른 교정을 JSON 으로 복사하는 화면(F8.3).
            MontageListCell(title = stringResource(Res.string.export_title), chevron = true, onClick = onOpenFlagExport)
            MontageListCell(title = stringResource(Res.string.app_info_title), chevron = true, onClick = onOpenAppInfo)
            onOpenDeveloperTools?.let {
                // 계측 테스트와 XCUITest 는 기기를 흔들 수 없어 이 줄로 디버깅 화면을 연다.
                MontageListCell(
                    title = stringResource(Res.string.settings_developer_tools),
                    modifier = Modifier.testTag("settings-developer-tools"),
                    chevron = true,
                    onClick = it,
                )
            }
        }
    }
    state.deletingKey?.let { provider ->
        MontageConfirmDialog(
            title = stringResource(Res.string.settings_key_delete_title, provider.label),
            message = stringResource(Res.string.settings_key_delete_message),
            confirm = stringResource(Res.string.settings_key_delete),
            cancel = stringResource(Res.string.settings_key_delete_cancel),
            onConfirm = { onIntent(SettingsIntent.DeleteKey) },
            onCancel = { onIntent(SettingsIntent.CancelKeyDeletion) },
        )
    }
    if (state.revokingConsent) {
        MontageConfirmDialog(
            title = stringResource(Res.string.settings_consent_revoke_title),
            message = stringResource(Res.string.settings_consent_revoke_message),
            confirm = stringResource(Res.string.settings_consent_revoke),
            cancel = stringResource(Res.string.settings_key_delete_cancel),
            onConfirm = { onIntent(SettingsIntent.RevokeConsent) },
            onCancel = { onIntent(SettingsIntent.CancelConsentRevoke) },
        )
    }
}

/** 서버가 없어 방침 문서는 저장소에 둔다. 스토어 등록 화면에도 같은 주소를 넣는다. */
private const val PRIVACY_POLICY_URL = "https://github.com/nosorae/malhaebom-opic/blob/main/docs/privacy-policy.md"

@Composable
private fun SectionLabel(text: StringResource) {
    Text(
        text = stringResource(text),
        style = MontageTheme.typography.label1.bold,
        color = MontageTheme.colors.foregroundNeutralSecondary,
        modifier = Modifier.padding(
            start = MontageSpacing.spacing20,
            end = MontageSpacing.spacing20,
            top = MontageSpacing.spacing24,
            bottom = MontageSpacing.spacing8,
        ),
    )
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MontageTheme.typography.label1.regular,
        color = MontageTheme.colors.foregroundNeutralSecondary,
        modifier = Modifier.padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing8),
    )
}

/** 동의했으면 철회 버튼을, 철회했으면 다시 동의할 수 있게 동의 화면으로 가는 줄을 보인다(F1.3). */
@Composable
private fun ConsentRow(given: Boolean, onRevoke: () -> Unit, onOpenConsent: () -> Unit) {
    MontageListCell(
        title = stringResource(Res.string.settings_consent),
        caption = stringResource(if (given) Res.string.settings_consent_given else Res.string.settings_consent_revoked),
        chevron = !given,
        onClick = if (given) null else onOpenConsent,
    ) {
        if (given) {
            MontageButton(
                text = stringResource(Res.string.settings_consent_revoke),
                onClick = onRevoke,
                variant = MontageButtonVariant.Text,
                type = MontageButtonType.Assistive,
                size = MontageButtonSize.Medium,
            )
        }
    }
}

/** 방금 불러온 결과. 틀린 파일이면 무엇이 틀렸는지 검사가 돌려준 이유를 그대로 보인다(F6.3). */
@Composable
private fun QuestionImportNotice(result: QuestionImportResult) {
    val text = when (result) {
        is QuestionImportResult.Imported -> stringResource(Res.string.settings_question_file_imported, result.count)
        QuestionImportResult.Unreadable -> stringResource(Res.string.settings_question_file_unreadable)
        is QuestionImportResult.Invalid -> stringResource(Res.string.settings_question_file_invalid, result.reason)
    }
    Text(
        text = text,
        style = MontageTheme.typography.label1.regular,
        color = if (result is QuestionImportResult.Imported) MontageTheme.colors.foregroundNeutralSecondary else MontageTheme.colors.foregroundNegativePrimary,
        modifier = Modifier
            .padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing8)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun KeyRow(key: KeyStatus, onOpen: () -> Unit, onDelete: () -> Unit) {
    MontageListCell(
        title = key.provider.label,
        caption = key.maskedKey?.let { stringResource(Res.string.settings_key_saved, it) } ?: stringResource(Res.string.settings_key_missing),
        chevron = !key.saved,
        onClick = onOpen,
    ) {
        if (key.saved) {
            MontageButton(
                text = stringResource(Res.string.settings_key_delete),
                onClick = onDelete,
                variant = MontageButtonVariant.Text,
                type = MontageButtonType.Assistive,
                size = MontageButtonSize.Medium,
            )
        }
    }
}

@Composable
private fun modelCaption(model: ModelInfo): String = stringResource(
    Res.string.settings_model_caption,
    model.id,
    stringResource(if (model.hearsAudio) Res.string.settings_model_hears_audio else Res.string.settings_model_transcribes),
    stringResource(if (model.stability == Stability.Stable) Res.string.settings_model_stable else Res.string.settings_model_preview),
)

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.System -> Res.string.settings_theme_system
    ThemeMode.Light -> Res.string.settings_theme_light
    ThemeMode.Dark -> Res.string.settings_theme_dark
}

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object SettingsSamples {
    private val stable = ModelInfo("gemini", "gemini-3.8-flash", "Gemini 3.8 Flash", hearsAudio = true, stability = Stability.Stable)
    private val preview = ModelInfo("gemini", "gemini-4-pro-preview", "Gemini 4 Pro Preview", hearsAudio = true, stability = Stability.Preview)

    val default = SettingsUiState(
        models = listOf(stable, preview),
        selectedModelId = stable.id,
        keys = listOf(KeyStatus(KeyProvider.Gemini, maskedKey = "AQ.…b1Ig")),
        targetGrades = listOf("IM1", "IM2", "IM3", "IH", "AL"),
        targetGrade = "IH",
    )
    val previewSelected = default.copy(selectedModelId = preview.id)
    val fellBack = default.copy(fellBackFrom = "gemini-2.0-flash", keys = listOf(KeyStatus(KeyProvider.Gemini, maskedKey = null)))
    val dark = default.copy(themeMode = ThemeMode.Dark)

    /** 동의를 철회했고, 타이머를 켰고, 불러온 파일이 있는데 방금 고른 파일은 틀렸다. */
    val practiceSet = default.copy(
        consentGiven = false,
        answerTimerOn = true,
        importedQuestionCount = 12,
        questionImport = QuestionImportResult.Invalid("id 가 겹친다: [my-1]"),
    )

    /** 기기 음성을 골랐는데 고품질 기기 음성이 없다. */
    val deviceVoice = default.copy(naturalVoiceOn = false, highQualityDeviceVoice = false)
    val imported = default.copy(answerTimerOn = true, importedQuestionCount = 12, questionImport = QuestionImportResult.Imported(12))
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    MontageTheme { SettingsScreen(SettingsSamples.practiceSet, onIntent = {}, onOpenKeyEntry = {}, onOpenFreeTierNotice = {}, onOpenFlagExport = {}, onOpenAppInfo = {}, onPickQuestionFile = {}) }
}
