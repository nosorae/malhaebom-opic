package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.ui.design.MontageBottomSheet
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageChip
import io.github.nosorae.aiopic.ui.design.MontageChipVariant
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.rememberMicPermission
import io.github.nosorae.aiopic.ui.design.rememberOpenAppSettings
import org.jetbrains.compose.resources.stringResource

/**
 * 연습 시작 시트의 바깥 겹. 시작을 누르면 마이크 권한을 받고 연습을 연 뒤 [onStart] 로 연습 중 화면을 연다.
 * 키가 없으면 [onOpenKeySettings] 로 키 입력을 연다. 바깥을 누르거나 뒤로가기를 누르면 [onDismiss] 다.
 */
@Composable
fun SpeakingStartRoute(viewModel: SpeakingStartViewModel, onStart: () -> Unit, onOpenKeySettings: () -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnStarted by rememberUpdatedState(onStart)
    LaunchedEffect(state.navigation) {
        if (state.navigation == SpeakingStartDestination.Running) {
            currentOnStarted()
            viewModel.onIntent(SpeakingStartIntent.NavigationHandled)
        }
    }
    val requestMic = rememberMicPermission()
    LaunchedEffect(state.needsMic) {
        if (state.needsMic) viewModel.onIntent(if (requestMic()) SpeakingStartIntent.MicGranted else SpeakingStartIntent.MicDenied)
    }
    SpeakingStartSheet(state, viewModel::onIntent, onOpenKeySettings, rememberOpenAppSettings(), onDismiss)
}

/** 연습 시작 시트(F16.2, F16.3). 시간, 소리 환경, 말로 하는 조작 안내, 대략 비용, 시작 버튼이다. */
@Composable
fun SpeakingStartSheet(state: SpeakingStartUiState, onIntent: (SpeakingStartIntent) -> Unit, onOpenKeySettings: () -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    MontageBottomSheet(onDismissRequest = onDismiss) {
        SpeakingStartContent(state, onIntent, onOpenKeySettings, onOpenSettings)
    }
}

@Composable
private fun SpeakingStartContent(state: SpeakingStartUiState, onIntent: (SpeakingStartIntent) -> Unit, onOpenKeySettings: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    // 큰 글자에서 안내가 길어져도 시작 버튼은 스크롤 밖 아래에 늘 보인다.
    Column(Modifier.padding(horizontal = MontageSpacing.spacing20).padding(bottom = MontageSpacing.spacing16)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
            Text(stringResource(Res.string.start_title), style = typography.heading2.bold, color = colors.foregroundNeutralPrimary, modifier = Modifier.semantics { heading() })
            Label(stringResource(Res.string.start_minutes))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                PRACTICE_MINUTES.forEach { minutes ->
                    MontageChip(stringResource(Res.string.start_minutes_value, minutes), onClick = { onIntent(SpeakingStartIntent.SelectMinutes(minutes)) }, active = minutes == state.minutes, variant = MontageChipVariant.Outlined)
                }
            }
            Label(stringResource(Res.string.start_route))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                AudioRoute.entries.forEach { route ->
                    MontageChip(stringResource(route.label), onClick = { onIntent(SpeakingStartIntent.SelectRoute(route)) }, active = route == state.route, variant = MontageChipVariant.Outlined)
                }
            }
            if (state.route == AudioRoute.Speaker) Body(stringResource(Res.string.start_speaker_hint))
            Text(stringResource(Res.string.start_commands), style = typography.body2.regular, color = colors.foregroundNeutralPrimary, modifier = Modifier.padding(top = MontageSpacing.spacing16))
            Text(stringResource(Res.string.start_cost, usd(state.costUsd)), style = typography.body2.medium, color = colors.foregroundNeutralPrimary, modifier = Modifier.padding(top = MontageSpacing.spacing8))
            Body(stringResource(Res.string.start_cost_help))
            if (!state.consented) Body(stringResource(Res.string.start_consent), Modifier.padding(top = MontageSpacing.spacing8))
            if (state.micDenied) Body(stringResource(Res.string.start_mic_denied), Modifier.padding(top = MontageSpacing.spacing8))
            if (!state.hasKey) Body(stringResource(Res.string.start_no_key), Modifier.padding(top = MontageSpacing.spacing8))
        }
        when {
            !state.hasKey -> MontageButton(
                text = stringResource(Res.string.start_open_key),
                onClick = onOpenKeySettings,
                variant = MontageButtonVariant.Outlined,
                size = MontageButtonSize.Medium,
                modifier = Modifier.fillMaxWidth().padding(top = MontageSpacing.spacing16),
            )

            state.micDenied -> MontageButton(
                text = stringResource(Res.string.start_open_settings),
                onClick = onOpenSettings,
                variant = MontageButtonVariant.Outlined,
                size = MontageButtonSize.Medium,
                modifier = Modifier.fillMaxWidth().padding(top = MontageSpacing.spacing16),
            )
        }
        // 키가 없으면 흐리게 둔다(F16.3).
        MontageButton(
            text = stringResource(if (state.consented) Res.string.start_button else Res.string.start_agree),
            onClick = { onIntent(SpeakingStartIntent.Start) },
            enabled = state.canStart,
            loading = state.starting,
            modifier = Modifier.fillMaxWidth().padding(top = if (state.hasKey && !state.micDenied) MontageSpacing.spacing16 else MontageSpacing.spacing8),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MontageTheme.typography.label1.bold, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = Modifier.padding(top = MontageSpacing.spacing16))
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = modifier)
}

private val AudioRoute.label
    get() = when (this) {
        AudioRoute.Earphones -> Res.string.start_route_earphones
        AudioRoute.Speaker -> Res.string.start_route_speaker
    }

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object SpeakingStartSamples {
    val earphones = SpeakingStartUiState(minutes = 20, route = AudioRoute.Earphones)
    val speaker = earphones.copy(route = AudioRoute.Speaker, consented = false)
    val noKey = earphones.copy(hasKey = false)
}

@Preview
@Composable
private fun SpeakingStartContentPreview() {
    MontageTheme { SpeakingStartContent(SpeakingStartSamples.speaker, {}, {}, {}) }
}
