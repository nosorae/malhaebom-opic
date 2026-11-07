package io.github.nosorae.aiopic.ui.featureexam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.question.model.Survey
import io.github.nosorae.aiopic.engine.question.model.SurveyOption
import io.github.nosorae.aiopic.engine.question.model.SurveyQuestion
import io.github.nosorae.aiopic.engine.question.model.Topic
import io.github.nosorae.aiopic.engine.question.model.TopicGroup
import io.github.nosorae.aiopic.engine.session.model.ExamStep
import io.github.nosorae.aiopic.engine.session.model.Recalibration
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
import io.github.nosorae.aiopic.ui.design.MontageListCell
import io.github.nosorae.aiopic.ui.design.MontageMotion
import io.github.nosorae.aiopic.ui.design.MontagePlayButton
import io.github.nosorae.aiopic.ui.design.MontageRadius
import io.github.nosorae.aiopic.ui.design.MontageRecordingButtons
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageStepTrail
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.github.nosorae.aiopic.ui.design.Res as DesignRes

/**
 * ViewModel 을 관측해 이동을 처리하는 바깥 겹. Play 를 누르면 마이크 권한부터 받는다.
 * 나간 뒤와 시험이 끝난 뒤 어디로 갈지는 화면을 등록한 쪽이 [onExit], [onFinish] 로 정한다.
 */
@Composable
fun ExamRoute(viewModel: ExamViewModel, onExit: () -> Unit, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnFinish by rememberUpdatedState(onFinish)
    // 마지막 문항까지 답했으면 축하 화면을 잠깐 보이고 리포트로 간다. 시간이 다 돼 끝났으면 바로 간다.
    var finishing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.navigation) {
        when (state.navigation) {
            ExamDestination.Exit -> currentOnExit()

            ExamDestination.Finished -> {
                if (state.answeredAll) {
                    finishing = true
                    delay(FINISH_HOLD_MILLIS)
                }
                currentOnFinish()
            }

            null -> return@LaunchedEffect
        }
        viewModel.onIntent(ExamIntent.NavigationHandled)
    }
    if (finishing) {
        ExamFinished(state.questionCount, viewModel.sessionId, modifier)
        return
    }
    val requestMic = rememberMicPermission()
    val openSettings = rememberOpenAppSettings()
    val scope = rememberCoroutineScope()
    ExamScreen(
        state = state,
        onOpenSettings = openSettings,
        onIntent = { intent ->
            if (intent == ExamIntent.PlayQuestion) {
                scope.launch { viewModel.onIntent(if (requestMic()) intent else ExamIntent.MicDenied) }
            } else {
                viewModel.onIntent(intent)
            }
        },
        modifier = modifier,
    )
}

/**
 * 상태만 받아 그리는 안쪽 겹. 회차의 지금 단계에 맞는 화면 하나를 그린다. 미리보기와 스크린샷 테스트가 이 함수를 쓴다.
 * 위쪽 X 는 바로 나가지 않고 나가면 무엇이 남는지 먼저 묻는다. 시험 중에는 시스템 뒤로가기가 막혀 있어 이 X 가 유일한 출구다.
 */
@Composable
fun ExamScreen(state: ExamUiState, onIntent: (ExamIntent) -> Unit, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    RecordHaptics(state.phase == QuestionPhase.Recording)
    var confirmingExit by rememberSaveable { mutableStateOf(false) }
    val onExit = { confirmingExit = true }
    if (confirmingExit) {
        MontageConfirmDialog(
            title = stringResource(Res.string.exam_exit_title),
            message = stringResource(state.exitMessage),
            confirm = stringResource(Res.string.exam_exit),
            cancel = stringResource(Res.string.exam_exit_cancel),
            onConfirm = {
                confirmingExit = false
                onIntent(ExamIntent.ExitExam)
            },
            onCancel = { confirmingExit = false },
        )
    }
    when (val step = state.step) {
        null -> StepScaffold(title = "", onExit = onExit, modifier = modifier) {}

        is ExamStep.Survey -> SurveyStep(state = state, onIntent = onIntent, onExit = onExit, modifier = modifier)

        ExamStep.LevelSelect -> ChoiceStep(
            title = stringResource(Res.string.exam_level_title),
            description = stringResource(Res.string.exam_level_description),
            selected = state.selectedLevel != null,
            onSubmit = { onIntent(ExamIntent.SubmitLevel) },
            saving = state.saving,
            onExit = onExit,
            modifier = modifier,
        ) {
            LevelChoices(count = state.levelCount, selected = state.selectedLevel?.minus(1), onSelect = { onIntent(ExamIntent.SelectLevel(it + 1)) })
        }

        ExamStep.Recalibrate -> ChoiceStep(
            title = stringResource(Res.string.exam_recalibrate_title),
            description = stringResource(Res.string.exam_recalibrate_description),
            selected = state.selectedRecalibration != null,
            onSubmit = { onIntent(ExamIntent.SubmitRecalibration) },
            saving = state.saving,
            onExit = onExit,
            modifier = modifier,
        ) {
            RecalibrationCards(selected = state.selectedRecalibration, onSelect = { onIntent(ExamIntent.SelectRecalibration(it)) })
        }

        ExamStep.Check, is ExamStep.Ask -> QuestionStep(
            title = if (step is ExamStep.Ask) {
                stringResource(Res.string.exam_question_title, state.questionNumber, state.questionCount)
            } else {
                stringResource(Res.string.exam_check_title)
            },
            description = if (step is ExamStep.Check) stringResource(Res.string.exam_check_description) else null,
            state = state,
            onPlay = { onIntent(ExamIntent.PlayQuestion) },
            onReplay = { onIntent(ExamIntent.ReplayQuestion) },
            onNext = { onIntent(ExamIntent.SubmitAnswer) },
            onOpenSettings = onOpenSettings,
            onExit = onExit,
            modifier = modifier,
        )
    }
}

/** 모든 단계가 같은 틀을 쓴다. 시험 중에는 탭이 없고 앞으로만 가서 위쪽에 나가기 X 하나만 둔다. */
@Composable
private fun StepScaffold(
    title: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    MontageScreen(
        modifier = modifier,
        topBar = {
            MontageTopBar(title = title) { ExitButton(onExit) }
        },
        bottomBar = { Row(Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing12)) { bottomBar() } },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = MontageSpacing.spacing16),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12),
        ) {
            content()
        }
    }
}

/** 서베이를 한 화면에 질문 하나씩 넘긴다. 마지막 묶음에서 다음을 누르면 서베이를 낸다(F2.2). */
@Composable
private fun SurveyStep(state: ExamUiState, onIntent: (ExamIntent) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    // 회차를 읽으며 서베이를 먼저 채우고 단계를 정해서 서베이 단계에서는 늘 있다.
    val survey = state.survey ?: return
    val haptics = LocalHaptics.current
    MontageScreen(
        modifier = modifier,
        topBar = { MontageTopBar(title = stringResource(Res.string.exam_survey_title)) { ExitButton(onExit) } },
        bottomBar = {
            SurveyButtons(
                state = survey,
                submitText = stringResource(Res.string.exam_next_step),
                saving = state.saving,
                onPrevious = { onIntent(ExamIntent.PreviousSurveyScreen) },
                onNext = { onIntent(ExamIntent.NextSurveyScreen) },
                onSubmit = {
                    haptics.play(HapticEffect.Tap)
                    onIntent(ExamIntent.SubmitSurvey)
                },
                modifier = Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing12),
            )
        },
    ) {
        SurveyFlow(survey, onChoose = { onIntent(ExamIntent.ChooseSurveyOption(it)) }, onToggle = { onIntent(ExamIntent.ToggleTopic(it)) })
    }
}

/** 하나를 골라 다음으로 가는 단계(수준 선택, 재조정). 고를 것은 [choices] 가 그린다. */
@Composable
private fun ChoiceStep(
    title: String,
    description: String,
    selected: Boolean,
    onSubmit: () -> Unit,
    saving: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    choices: @Composable () -> Unit,
) {
    val haptics = LocalHaptics.current
    StepScaffold(
        title = title,
        onExit = onExit,
        modifier = modifier,
        bottomBar = {
            NextButton(enabled = selected, loading = saving) {
                haptics.play(HapticEffect.Tap)
                onSubmit()
            }
        },
    ) {
        Description(description)
        choices()
    }
}

/**
 * 질문 글자는 보이지 않는다(F2.3). 한 문항 연습과 같은 부품을 같은 순서로 쓴다. 위에서부터 시험에만 있는 문항 진행 점과 남은 시간,
 * 단계 표시(듣기, 답하기. 시험 중에는 피드백을 보이지 않는다), 가운데 주인공(Play 또는 목소리 띠)과 지금 하는 일이다.
 * 규칙은 첫 화면인 장비 점검에서 Play 전에 한 줄 안내로만 보이고, 그 뒤로는 단계 표시와 버튼 채움이 대신 보인다.
 */
@Composable
private fun QuestionStep(
    title: String,
    description: String?,
    state: ExamUiState,
    onPlay: () -> Unit,
    onReplay: () -> Unit,
    onNext: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state.phase == QuestionPhase.Recording
    val haptics = LocalHaptics.current
    StepScaffold(
        title = title,
        onExit = onExit,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        bottomBar = {
            if (recording) {
                MontageRecordingButtons(
                    recorded = state.recorded,
                    replayWindow = ReplayWindow,
                    nextDelay = NextDelay,
                    canReplay = state.canReplay,
                    canNext = state.canNext,
                    saving = state.saving,
                    onReplay = onReplay,
                    onNext = onNext,
                    tagPrefix = "exam",
                )
            }
        },
    ) {
        if (state.step is ExamStep.Ask && state.questionCount > 0) {
            MontageStepTrail(total = state.questionCount, done = state.questionNumber - 1, modifier = Modifier.padding(horizontal = Gutter))
        }
        state.remaining?.let { RemainingTime(it, state.timeLimit) }
        MontageAnswerSteps(
            current = if (recording) AnswerStep.Answer else AnswerStep.Listen,
            withFeedback = false,
            modifier = Modifier.padding(horizontal = Gutter + MontageSpacing.spacing48),
        )
        description?.let { Description(it, TextAlign.Center) }
        Spacer(Modifier.height(MontageSpacing.spacing16))
        if (recording) {
            MontageVoiceRibbon(state.voiceLevel, Modifier.testTag("exam-voice"))
        } else {
            // 질문을 다 읽어야 녹음이 시작돼서 그동안 Play 가 소리 물결로 바뀐다. 모델 질문을 기다리면 아래 문구가 무엇을 만드는지 알린다(F6.1).
            val retry = state.error in RetryErrors
            MontagePlayButton(
                text = stringResource(if (retry) Res.string.exam_retry else Res.string.exam_play),
                busy = state.phase == QuestionPhase.Speaking,
                onClick = {
                    haptics.play(HapticEffect.Tap)
                    onPlay()
                },
                icon = if (retry) DesignRes.drawable.icon_normal_refresh else DesignRes.drawable.icon_normal_play,
                modifier = Modifier.testTag("exam-play"),
            )
        }
        PhaseText(state)
        if (state.step is ExamStep.Check && state.phase == QuestionPhase.Ready && state.error == null) Description(stringResource(Res.string.exam_hint), TextAlign.Center)
        if (state.questionNotice == QuestionNotice.BuiltIn) Description(stringResource(Res.string.exam_question_built_in), TextAlign.Center)
        state.error?.let {
            Text(
                text = stringResource(it.message),
                style = MontageTheme.typography.body2.medium,
                color = MontageTheme.colors.foregroundNegativePrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Gutter).testTag("exam-error"),
            )
        }
        if (state.error == ExamError.MicDenied) {
            MontageButton(
                text = stringResource(Res.string.exam_open_settings),
                onClick = onOpenSettings,
                variant = MontageButtonVariant.Outlined,
                type = MontageButtonType.Assistive,
                size = MontageButtonSize.Medium,
                modifier = Modifier.testTag("exam-open-settings"),
            )
        }
    }
}

/** 남은 시간을 40분 중 남은 만큼의 원과 숫자로 보인다. 원은 1초마다 값만 바뀌고 움직이지 않는다(F2.5). */
@Composable
private fun RemainingTime(remaining: Duration, limit: Duration?) {
    val colors = MontageTheme.colors
    val style = MontageTheme.typography.label1.bold
    // 원은 옆 글자 높이에 맞춰 글자 크기 설정을 따른다.
    val ring = with(LocalDensity.current) { style.fontSize.toDp() }
    Row(
        modifier = Modifier.padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (limit != null) {
            val left = (remaining / limit).toFloat().coerceIn(0f, 1f)
            Canvas(Modifier.size(ring)) {
                val stroke = size.minDimension * 0.18f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(colors.lineNeutralPrimary, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(colors.brandDecoration, -90f, 360f * left, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(
            text = stringResource(Res.string.exam_remaining, remaining.clockText()),
            style = style,
            color = MontageTheme.colors.foregroundNeutralSecondary,
        )
    }
}

/**
 * 수준 1~[count] 목록. 단계마다 높아지는 막대가 붙어 높을수록 어렵다는 것이 보이고,
 * 고르면 옅은 강조 띠가 고른 줄로 미끄러져 간다. 처음 고를 때는 띠가 그 줄에 바로 나타난다.
 */
@Composable
private fun LevelChoices(count: Int, selected: Int?, onSelect: (Int) -> Unit) {
    val haptics = LocalHaptics.current
    val band = MontageTheme.colors.surfaceBrandPrimaryOpacity8
    val position = remember { Animatable(selected?.toFloat() ?: -1f) }
    val settle = MontageMotion.settle<Float>()
    LaunchedEffect(selected) {
        val target = selected?.toFloat() ?: return@LaunchedEffect
        if (position.value < 0f) position.snapTo(target) else position.animateTo(target, settle)
    }
    Column(
        Modifier.padding(horizontal = MontageSpacing.spacing8).drawBehind {
            if (position.value < 0f) return@drawBehind
            val row = size.height / count
            drawRoundRect(band, Offset(0f, row * position.value), Size(size.width, row), CornerRadius(MontageRadius.radius12.toPx()))
        },
    ) {
        repeat(count) { index ->
            MontageListCell(
                title = stringResource(Res.string.exam_level_item, index + 1),
                selected = index == selected,
                onClick = {
                    haptics.play(HapticEffect.Selection)
                    onSelect(index)
                },
                modifier = Modifier.testTag("exam-choice-$index"),
            ) {
                DifficultyBars(filled = index + 1, total = count, strong = index == selected)
            }
        }
    }
}

/** 재조정 셋을 카드로 보인다. 고른 카드는 강조색 테두리로 앞으로 나오고 나머지는 살짝 물러난다. */
@Composable
private fun RecalibrationCards(selected: Recalibration?, onSelect: (Recalibration) -> Unit) {
    val haptics = LocalHaptics.current
    val colors = MontageTheme.colors
    val shape = RoundedCornerShape(MontageRadius.radius16)
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        Recalibration.entries.forEachIndexed { index, choice ->
            val chosen = choice == selected
            val scale by animateFloatAsState(if (selected == null || chosen) 1f else 0.98f, MontageMotion.spec(MontageMotion.SHORT_MILLIS))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(shape)
                    .background(if (chosen) colors.surfaceBrandPrimaryOpacity8 else colors.surfaceNeutralSecondary)
                    .border(1.dp, if (chosen) colors.lineBrandPrimary else Color.Transparent, shape)
                    .selectable(selected = chosen, role = Role.RadioButton) {
                        haptics.play(HapticEffect.Selection)
                        onSelect(choice)
                    }
                    .padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing20)
                    .testTag("exam-choice-$index"),
                horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(choice.label),
                    style = if (chosen) MontageTheme.typography.body1.bold else MontageTheme.typography.body1.medium,
                    color = if (chosen) colors.foregroundBrandPrimary else colors.foregroundNeutralPrimary,
                    modifier = Modifier.weight(1f),
                )
                DifficultyBars(filled = index + 1, total = Recalibration.entries.size, strong = chosen)
            }
        }
    }
}

/** 왼쪽부터 높아지는 막대 [total] 개 중 [filled] 개를 채운다. 난이도가 높을수록 많이 찬다. [strong] 이면 강조색이다. */
@Composable
private fun DifficultyBars(filled: Int, total: Int, strong: Boolean) {
    val on = if (strong) MontageTheme.colors.brandDecoration else MontageTheme.colors.foregroundNeutralTertiary
    val off = MontageTheme.colors.lineNeutralPrimary
    Canvas(Modifier.size(width = MontageSpacing.spacing6 * total, height = MontageSpacing.spacing16).clearAndSetSemantics {}) {
        val slot = size.width / total
        val bar = slot * 0.6f
        for (i in 0 until total) {
            val height = size.height * (i + 1) / total
            drawRoundRect(
                color = if (i < filled) on else off,
                topLeft = Offset(slot * i + (slot - bar) / 2, size.height - height),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** 가운데 주인공 아래 지금 하는 일. 질문을 기다리면 무엇을 만드는지, 오래 걸리면 연결 상태를 보라고 쓴다. Play 전에는 없다. */
@Composable
private fun PhaseText(state: ExamUiState) {
    val notice = state.questionNotice
    val text = when {
        notice is QuestionNotice.Making -> stringResource(
            if (notice.recalibrated) Res.string.exam_question_making_difficulty else Res.string.exam_question_making_topics,
            notice.first,
            notice.last,
        )

        notice == QuestionNotice.Slow -> stringResource(Res.string.exam_question_slow)

        state.phase == QuestionPhase.Speaking -> stringResource(Res.string.exam_speaking)

        state.phase == QuestionPhase.Recording && state.answerTimer -> stringResource(Res.string.exam_recording_timer, state.recorded.clockText())

        state.phase == QuestionPhase.Recording -> stringResource(Res.string.exam_recording)

        else -> return
    }
    Text(
        text = text,
        style = MontageTheme.typography.heading2.bold,
        color = if (state.phase == QuestionPhase.Recording) MontageTheme.colors.foregroundBrandPrimary else MontageTheme.colors.foregroundNeutralPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Gutter).testTag("exam-phase"),
    )
}

@Composable
private fun Description(text: String, align: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        style = MontageTheme.typography.body1.regular,
        color = MontageTheme.colors.foregroundNeutralSecondary,
        textAlign = align,
        modifier = Modifier.padding(horizontal = Gutter),
    )
}

/** 시험 중에는 탭이 없고 앞으로만 가서 위쪽에 나가기 X 하나만 둔다. */
@Composable
private fun ExitButton(onExit: () -> Unit) {
    MontageIconButton(DesignRes.drawable.icon_normal_close, stringResource(Res.string.exam_exit), onExit, Modifier.testTag("exam-exit"))
}

/**
 * 나가면 실제로 남는 것. 서베이는 내기 전까지 이번에 고른 답을 저장하지 않는다. 그 밖의 단계는 회차가 단계마다 저장되고
 * 쓴 녹음 조각도 남아 이어 보기로 같은 곳에서 잇는다(F3). 40분은 이 화면이 떠 있는 동안만 흐른다(F2.5).
 */
private val ExamUiState.exitMessage: StringResource
    get() = when {
        step is ExamStep.Survey -> Res.string.exam_exit_message_survey
        remaining != null -> Res.string.exam_exit_message_timer
        else -> Res.string.exam_exit_message
    }

/** 저장하는 동안([loading]) 진행 표시를 보인다. 그동안 다른 입력은 ViewModel 이 받지 않는다. */
@Composable
private fun NextButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    MontageButton(
        text = stringResource(Res.string.exam_next_step),
        onClick = onClick,
        enabled = enabled,
        loading = loading,
        modifier = Modifier.fillMaxWidth().testTag("exam-next-step"),
    )
}

private val Gutter = MontageSpacing.spacing20

/** 분:초. 40분 타이머와 녹음 시간에 쓴다. */

private val Recalibration.label: StringResource
    get() = when (this) {
        Recalibration.Easier -> Res.string.exam_recalibrate_easier
        Recalibration.Same -> Res.string.exam_recalibrate_same
        Recalibration.Harder -> Res.string.exam_recalibrate_harder
    }

/** 같은 문항을 다시 녹음하면 풀리는 오류. 이때 Play 가 "다시 녹음" 이 된다. */
private val RetryErrors = setOf(ExamError.MicUnavailable, ExamError.RecordingFailed)

private val ExamError.message: StringResource
    get() = when (this) {
        ExamError.MicDenied -> Res.string.exam_error_mic_denied
        ExamError.MicUnavailable -> Res.string.exam_error_mic
        ExamError.RecordingFailed -> Res.string.exam_error_recording
        ExamError.SpeechFailed -> Res.string.exam_error_speech
        ExamError.QuestionUnavailable -> Res.string.exam_error_question
    }

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. 화면마다 하나, 문항 화면은 진행마다 하나다. */
internal object ExamSamples {
    private val ask = ExamStep.Ask(QuestionType("routine"))

    private val surveySample = Survey(
        questions = listOf(
            SurveyQuestion(
                "work",
                "지금 하는 일은 어느 쪽에 가깝나요?",
                listOf(SurveyOption("company", "회사나 사업"), SurveyOption("home-office", "집에서 하는 일이나 사업"), SurveyOption("teacher", "가르치는 일"), SurveyOption("none", "일해 본 적 없어요")),
            ),
        ),
        groups = listOf(
            TopicGroup("leisure", "쉬는 날에는 주로 무엇을 하나요?", 2, listOf("영화보기", "클럽/나이트클럽 가기", "공연보기", "콘서트보기", "박물관가기", "공원가기", "캠핑하기", "해변가기", "스포츠 관람", "주거 개선").map { Topic(it, it) }),
            TopicGroup("trips", "휴가나 출장은 어떻게 보내나요?", 1, listOf("국내출장", "해외출장", "집에서 보내는 휴가", "국내 여행", "해외 여행").map { Topic(it, it) }),
        ),
        minTopics = 12,
    )

    val survey = ExamUiState(step = ExamStep.Survey(minTopics = 12), survey = SurveyState(surveySample, choices = mapOf("work" to "company")))
    val surveyGroup = survey.copy(survey = survey.survey!!.copy(screen = 1, topics = setOf("영화보기", "공연보기", "스포츠 관람")))
    val surveyShort = survey.copy(survey = survey.survey!!.copy(screen = 2, topics = setOf("영화보기", "공연보기", "국내 여행")))
    val surveyReady = survey.copy(survey = survey.survey!!.copy(screen = 2, topics = surveySample.groups[0].topics.map { it.id }.toSet() + "국내 여행" + "해외 여행"))
    val level = ExamUiState(step = ExamStep.LevelSelect, levelCount = 6, selectedLevel = 4)
    val check = ExamUiState(step = ExamStep.Check)
    val checkRecording = check.copy(phase = QuestionPhase.Recording, recorded = 6.seconds, replayUsed = true, voiceLevel = 0.45f)
    val questionReady = ExamUiState(step = ask, questionNumber = 3, questionCount = 15, remaining = 36.minutes + 12.seconds, timeLimit = 40.minutes)
    val questionSpeaking = questionReady.copy(phase = QuestionPhase.Speaking)
    val questionLoading = questionSpeaking.copy(questionNumber = 2, questionNotice = QuestionNotice.Making(2, 7, recalibrated = false))
    val questionLoadingRecalibrated = questionSpeaking.copy(questionNumber = 8, questionNotice = QuestionNotice.Making(8, 15, recalibrated = true))
    val questionSlow = questionSpeaking.copy(questionNotice = QuestionNotice.Slow)
    val questionReplayable = questionReady.copy(phase = QuestionPhase.Recording, recorded = 3.seconds, voiceLevel = 0.6f)
    val questionBuiltIn = questionReplayable.copy(questionNotice = QuestionNotice.BuiltIn)
    val questionNextable = questionReady.copy(phase = QuestionPhase.Recording, recorded = 12.seconds, replayUsed = true, voiceLevel = 0.3f)
    val questionSaving = questionNextable.copy(saving = true)
    val surveySaving = surveyReady.copy(saving = true)
    val questionTimer = questionReplayable.copy(answerTimer = true)
    val micError = check.copy(error = ExamError.MicDenied)
    val recordingError = questionReady.copy(error = ExamError.RecordingFailed)
    val recalibrate = ExamUiState(step = ExamStep.Recalibrate, remaining = 21.minutes, selectedRecalibration = Recalibration.Same)
}

@Preview
@Composable
private fun ExamScreenPreview() {
    MontageTheme { ExamScreen(state = ExamSamples.questionReplayable, onIntent = {}) }
}

/** 다 마친 축하(1.4초)가 끝나고 문구를 읽을 틈까지 보인다. 동작 줄이기여도 같다. */
private const val FINISH_HOLD_MILLIS = 2_000L
