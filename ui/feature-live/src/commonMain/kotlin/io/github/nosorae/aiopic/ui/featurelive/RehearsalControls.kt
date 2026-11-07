package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.nosorae.aiopic.engine.coach.CoachPhase
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageVoiceRibbon
import io.github.nosorae.aiopic.ui.design.clockText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 리허설 단계의 아래쪽(F13.1). 코치 연결이 닫혀 있어 대화 버튼 대신 녹음 시작, 녹음 중 목소리 띠와 시간과 끝내기, 받아쓰는 중, 실패 이유와 다시 하기, 리허설 건너뛰기를 보인다.
 * 소재 메모와 표현 카드는 위쪽 대화 화면에 그대로 있다.
 */
@Composable
internal fun RehearsalControls(state: RehearsalUiState, onIntent: (LiveCoachIntent) -> Unit) {
    Column(
        Modifier.padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing12),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
    ) {
        val message = state.error?.message ?: when (state.step) {
            RehearsalStep.Ready -> Res.string.live_rehearse_hint
            RehearsalStep.Recording -> null
            RehearsalStep.Transcribing -> Res.string.live_rehearse_transcribing
        }
        message?.let { Text(stringResource(it), style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralPrimary) }
        if (state.step == RehearsalStep.Recording) {
            Text(
                stringResource(Res.string.live_rehearse_recording, state.recorded.clockText()),
                style = MontageTheme.typography.heading2.bold,
                color = MontageTheme.colors.foregroundBrandPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            MontageVoiceRibbon(state.voiceLevel)
        }
        val (text, intent) = when {
            state.step == RehearsalStep.Recording -> Res.string.live_rehearse_stop to RehearsalIntent.StopRecording
            state.step == RehearsalStep.Transcribing -> Res.string.live_rehearse_transcribing_button to null
            state.canRetryTranscript -> Res.string.live_rehearse_retry to RehearsalIntent.RetryTranscript
            state.error != null -> Res.string.live_rehearse_record_again to RehearsalIntent.StartRecording
            else -> Res.string.live_rehearse_start to RehearsalIntent.StartRecording
        }
        MontageButton(text = stringResource(text), onClick = { intent?.let(onIntent) }, loading = intent == null, modifier = Modifier.fillMaxWidth())
        // 받아쓰기가 계속 실패하거나 리허설을 하기 싫을 때 빠져나간다. 첫 리허설이면 스크립트 정리로, 두 번째면 피드백으로 간다(F13.3).
        if (state.step == RehearsalStep.Ready) {
            MontageButton(
                text = stringResource(Res.string.live_rehearse_skip),
                onClick = { onIntent(LiveCoachIntent.NextPhase) },
                modifier = Modifier.fillMaxWidth(),
                variant = MontageButtonVariant.Outlined,
                type = MontageButtonType.Assistive,
            )
        }
    }
}

private val RehearsalError.message: StringResource
    get() = when (this) {
        RehearsalError.MicUnavailable -> Res.string.live_rehearse_error_mic
        RehearsalError.RecordingFailed -> Res.string.live_rehearse_error_recording
        RehearsalError.NoSpeech -> Res.string.live_rehearse_error_no_speech
        RehearsalError.InvalidKey -> Res.string.live_rehearse_error_key
        RehearsalError.TranscriptFailed -> Res.string.live_rehearse_error_transcript
    }

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. 대화 화면 표본을 리허설과 피드백 단계로 옮긴다. */
internal object RehearsalSamples {
    private val base = LiveCoachSamples.exploring.copy(micLevel = 0f)
    private val rehearse = base.coach!!.copy(phase = CoachPhase.Rehearse)

    val ready = base.copy(coach = rehearse)
    val recording = ready.copy(rehearsal = RehearsalUiState(RehearsalStep.Recording, recorded = 83.seconds, voiceLevel = 0.5f))
    val transcriptFailed = ready.copy(rehearsal = RehearsalUiState(error = RehearsalError.TranscriptFailed))
    val feedbackOnce = base.copy(coach = rehearse.copy(phase = CoachPhase.Feedback, rehearsals = listOf("I went to Busan with my sister.")))
    val feedbackTwice = feedbackOnce.copy(coach = feedbackOnce.coach!!.copy(rehearsals = listOf("first", "second")))
}
