package io.github.nosorae.aiopic.ui.featurepractice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.libs.haptics.api.HapticEffect
import io.github.nosorae.aiopic.ui.design.AnswerStep
import io.github.nosorae.aiopic.ui.design.LocalHaptics
import io.github.nosorae.aiopic.ui.design.MontageAnswerSteps
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageConfirmDialog
import io.github.nosorae.aiopic.ui.design.MontageIconButton
import io.github.nosorae.aiopic.ui.design.MontageMotion
import io.github.nosorae.aiopic.ui.design.MontagePlayButton
import io.github.nosorae.aiopic.ui.design.MontageProgressBar
import io.github.nosorae.aiopic.ui.design.MontageRadius
import io.github.nosorae.aiopic.ui.design.MontageRecordingButtons
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.design.MontageVoiceRibbon
import io.github.nosorae.aiopic.ui.design.RecordHaptics
import io.github.nosorae.aiopic.ui.design.clockText
import io.github.nosorae.aiopic.ui.design.icon_normal_close
import io.github.nosorae.aiopic.ui.design.icon_normal_play
import io.github.nosorae.aiopic.ui.design.icon_normal_refresh
import io.github.nosorae.aiopic.ui.design.rememberElapsedFraction
import io.github.nosorae.aiopic.ui.design.rememberMicPermission
import io.github.nosorae.aiopic.ui.design.rememberOpenAppSettings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.github.nosorae.aiopic.ui.design.Res as DesignRes

/**
 * ViewModel 을 관측해 이동을 처리하는 바깥 겹. 질문을 고르면 바로 읽기 시작하고, 읽기 전에 마이크 권한부터 받는다.
 * 나간 뒤와 답변을 낸 뒤 어디로 갈지는 화면을 등록한 쪽이 [onExit], [onFeedback] 으로 정한다. [onFeedback] 은 답변 리포트를 연다.
 */
@Composable
fun PracticeRoute(viewModel: PracticeViewModel, onExit: () -> Unit, onFeedback: (answerId: String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnFeedback by rememberUpdatedState(onFeedback)
    LaunchedEffect(state.navigation) {
        when (val destination = state.navigation) {
            PracticeDestination.Exit -> currentOnExit()
            is PracticeDestination.Feedback -> currentOnFeedback(destination.answerId)
            null -> return@LaunchedEffect
        }
        viewModel.onIntent(PracticeIntent.NavigationHandled)
    }
    val requestMic = rememberMicPermission()
    val openSettings = rememberOpenAppSettings()
    val scope = rememberCoroutineScope()
    val onIntent: (PracticeIntent) -> Unit = { intent ->
        if (intent == PracticeIntent.PlayQuestion) {
            scope.launch { viewModel.onIntent(if (requestMic()) intent else PracticeIntent.MicDenied) }
        } else {
            viewModel.onIntent(intent)
        }
    }
    // 연습 탭에서 Play 를 누르고 들어왔으니 질문을 고르는 대로 한 번 읽는다. 화면을 돌리거나 되살려도 다시 읽지 않는다.
    var autoPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.canPlay) {
        if (state.canPlay && !autoPlayed) {
            autoPlayed = true
            onIntent(PracticeIntent.PlayQuestion)
        }
    }
    PracticeScreen(state = state, onOpenSettings = openSettings, onIntent = onIntent, modifier = modifier)
}

/**
 * 상태만 받아 그리는 안쪽 겹. 질문 글자는 보이지 않는다. 미리보기와 스크린샷 테스트가 이 함수를 쓴다.
 * 위에서부터 단계 표시(듣기, 답하기, 피드백), 왜 이 질문인지(약한 유형과 점수), 가운데 주인공(Play 또는 목소리 띠)이다.
 * 규칙은 Play 전에 한 줄 안내로만 보이고, 녹음이 시작되면 버튼 채움이 대신 보인다.
 * 녹음 중에 X 나 시스템 뒤로가기로 나가면 그 답변은 저장되지 않아서 먼저 묻는다. 녹음 전에는 잃을 것이 없어 바로 나간다.
 */
@Composable
fun PracticeScreen(state: PracticeUiState, onIntent: (PracticeIntent) -> Unit, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    val haptics = LocalHaptics.current
    val recording = state.phase == PracticePhase.Recording
    RecordHaptics(recording)
    var confirmingExit by rememberSaveable { mutableStateOf(false) }
    // ponytail: 실험 API 다. 바뀌면 navigationevent 의 NavigationBackHandler 로 옮긴다.
    @OptIn(ExperimentalComposeUiApi::class)
    BackHandler(enabled = recording) { confirmingExit = true }
    if (confirmingExit) {
        MontageConfirmDialog(
            title = stringResource(Res.string.practice_exit_title),
            message = stringResource(Res.string.practice_exit_message),
            confirm = stringResource(Res.string.practice_exit),
            cancel = stringResource(Res.string.practice_exit_cancel),
            onConfirm = {
                confirmingExit = false
                onIntent(PracticeIntent.ExitPractice)
            },
            onCancel = { confirmingExit = false },
        )
    }
    MontageScreen(
        modifier = modifier,
        topBar = {
            MontageTopBar(title = stringResource(Res.string.practice_title)) {
                MontageIconButton(
                    icon = DesignRes.drawable.icon_normal_close,
                    contentDescription = stringResource(Res.string.practice_exit),
                    onClick = { if (recording) confirmingExit = true else onIntent(PracticeIntent.ExitPractice) },
                    modifier = Modifier.testTag("practice-exit"),
                )
            }
        },
        bottomBar = {
            if (recording) {
                MontageRecordingButtons(
                    recorded = state.recorded,
                    replayWindow = ReplayWindow,
                    nextDelay = NextDelay,
                    canReplay = state.canReplay,
                    canNext = state.canNext,
                    saving = state.submitting,
                    onReplay = { onIntent(PracticeIntent.ReplayQuestion) },
                    onNext = { onIntent(PracticeIntent.SubmitAnswer) },
                    modifier = Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing12),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = MontageSpacing.spacing16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16),
        ) {
            MontageAnswerSteps(
                current = when {
                    state.submitting -> AnswerStep.Feedback
                    recording -> AnswerStep.Answer
                    else -> AnswerStep.Listen
                },
                withFeedback = true,
                modifier = Modifier.padding(horizontal = Gutter + MontageSpacing.spacing8),
            )
            when {
                state.script != null -> ScriptPanel(state.script)
                state.question != null -> WhyThisQuestion(state.weakness)
            }
            Spacer(Modifier.height(MontageSpacing.spacing16))
            when {
                recording -> MontageVoiceRibbon(state.voiceLevel)

                // 낼 질문이 없으면 지금 할 일이 없어서 오류 문구만 보인다.
                state.question != null || state.error == null -> MontagePlayButton(
                    text = stringResource(if (state.error in RetryErrors) Res.string.practice_retry else Res.string.practice_play),
                    busy = state.preparing,
                    onClick = {
                        haptics.play(HapticEffect.Tap)
                        onIntent(PracticeIntent.PlayQuestion)
                    },
                    enabled = state.canPlay,
                    icon = if (state.error in RetryErrors) DesignRes.drawable.icon_normal_refresh else DesignRes.drawable.icon_normal_play,
                )
            }
            val caption = when {
                recording -> stringResource(Res.string.practice_recording, state.recorded.clockText())
                state.phase == PracticePhase.Speaking -> stringResource(Res.string.practice_speaking)
                state.question == null && state.error == null -> stringResource(Res.string.practice_loading)
                else -> null
            }
            caption?.let {
                Text(
                    text = it,
                    style = MontageTheme.typography.heading2.bold,
                    color = if (recording) MontageTheme.colors.foregroundBrandPrimary else MontageTheme.colors.foregroundNeutralPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            if (state.canPlay && state.error == null) {
                // ponytail: 들어올 때마다 Play 전에 한 번 보인다. 익숙한 사용자가 거슬려 하면 본 적 있는지 설정에 남긴다.
                Text(
                    text = stringResource(Res.string.practice_hint),
                    style = MontageTheme.typography.body2.regular,
                    color = MontageTheme.colors.foregroundNeutralSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Gutter),
                )
            }
            state.error?.let {
                Text(
                    text = stringResource(it.message),
                    style = MontageTheme.typography.body2.medium,
                    color = MontageTheme.colors.foregroundNegativePrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Gutter).testTag("practice-error"),
                )
            }
            if (state.error == PracticeError.MicDenied) {
                MontageButton(
                    text = stringResource(Res.string.practice_open_settings),
                    onClick = onOpenSettings,
                    variant = MontageButtonVariant.Outlined,
                    type = MontageButtonType.Assistive,
                    size = MontageButtonSize.Medium,
                )
            }
        }
    }
}

/** 왜 이 질문인지. 약한 유형 이름과 점수, 점수 막대를 한 줄로 보인다. 분석한 답변이 없으면 모든 유형에서 골랐다고만 쓴다. */
@Composable
internal fun WhyThisQuestion(weakness: PracticeWeakness?) {
    val colors = MontageTheme.colors
    Row(
        modifier = Modifier
            .padding(horizontal = Gutter)
            .clip(RoundedCornerShape(MontageRadius.radius10))
            .background(colors.surfaceNeutralSecondary)
            .padding(horizontal = MontageSpacing.spacing12, vertical = MontageSpacing.spacing8)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (weakness == null) {
            Text(stringResource(Res.string.practice_any_type), style = MontageTheme.typography.label1.medium, color = colors.foregroundNeutralSecondary)
            return@Row
        }
        // 큰 글자에서는 유형 이름 쪽이 줄을 바꾸고 점수는 한 줄로 남는다.
        Text(
            text = buildAnnotatedString {
                append(stringResource(Res.string.practice_weak_type))
                append("  ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.foregroundNeutralPrimary)) { append(weakness.typeName) }
            },
            style = MontageTheme.typography.label1.medium,
            color = colors.foregroundNeutralSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        MontageProgressBar(weakness.score / 100f, Modifier.width(MontageSpacing.spacing32).height(MontageSpacing.spacing4))
        Text(stringResource(Res.string.practice_weak_score, weakness.score), style = MontageTheme.typography.label1.bold, color = colors.foregroundBrandPrimary)
    }
}

/** 스크립트로 연습할 때 보는 스크립트(F14.5). 제목 줄을 눌러 접고 펴며 처음엔 펴 둔다. */
@Composable
internal fun ScriptPanel(script: String) {
    var open by rememberSaveable { mutableStateOf(true) }
    val colors = MontageTheme.colors
    val toggle = stringResource(if (open) Res.string.practice_script_open else Res.string.practice_script_closed)
    Column(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(MontageRadius.radius12))
            .background(colors.surfaceNeutralSecondary),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MontageSpacing.spacing48)
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = MontageSpacing.spacing12)
                .testTag("practice-script-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.practice_script), style = MontageTheme.typography.label1.bold, color = colors.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
            Text(toggle, style = MontageTheme.typography.label1.medium, color = colors.foregroundBrandPrimary)
        }
        if (open) {
            Text(
                script,
                style = MontageTheme.typography.body2.regular,
                color = colors.foregroundNeutralPrimary,
                modifier = Modifier.padding(start = MontageSpacing.spacing12, end = MontageSpacing.spacing12, bottom = MontageSpacing.spacing12),
            )
        }
    }
}

internal val Gutter = MontageSpacing.spacing20

/** 다시 녹음하면 풀리는 오류. 이때 Play 가 "다시 녹음" 이 된다. */
private val RetryErrors = setOf(PracticeError.MicUnavailable, PracticeError.RecordingFailed)

private val PracticeError.message: StringResource
    get() = when (this) {
        PracticeError.MicDenied -> Res.string.practice_error_mic_denied
        PracticeError.MicUnavailable -> Res.string.practice_error_mic
        PracticeError.RecordingFailed -> Res.string.practice_error_recording
        PracticeError.SpeechFailed -> Res.string.practice_error_speech
        PracticeError.QuestionUnavailable -> Res.string.practice_error_question
        PracticeError.ScriptQuestionMissing -> Res.string.practice_error_script_question
    }

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. 진행마다 하나다. */
internal object PracticeSamples {
    private val question = Question(id = "q1", text = "Tell me about your daily routine.", type = QuestionType("routine"), topic = "일상")

    val loading = PracticeUiState()
    val ready = PracticeUiState(question = question, weakness = PracticeWeakness("롤플레이 해결", 42))
    val speaking = ready.copy(phase = PracticePhase.Speaking)
    val replayable = ready.copy(phase = PracticePhase.Recording, recorded = 3.seconds, voiceLevel = 0.6f)
    val nextable = ready.copy(phase = PracticePhase.Recording, recorded = 12.seconds, replayUsed = true, voiceLevel = 0.3f)
    val saving = nextable.copy(saving = true, submitting = true)
    val exiting = replayable.copy(saving = true)
    val micError = ready.copy(error = PracticeError.MicDenied)
    val recordingError = ready.copy(error = PracticeError.RecordingFailed)
    val noQuestion = PracticeUiState(error = PracticeError.QuestionUnavailable)
    val scriptRecording = replayable.copy(
        weakness = null,
        script = "I want to talk about a trip to Busan that I took with my sister last summer.\n\n" +
            "We stayed near Haeundae beach for three days. On the last night we watched fireworks over the sea, and it was an absolute blast.\n\n" +
            "That trip was special because I spent real time with my sister.",
    )
}

@Preview
@Composable
private fun PracticeScreenPreview() {
    MontageTheme { PracticeScreen(state = PracticeSamples.replayable, onIntent = {}) }
}
