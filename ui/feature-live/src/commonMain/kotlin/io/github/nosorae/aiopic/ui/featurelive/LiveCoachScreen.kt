package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.coach.CoachPhase
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.CoachStatus
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.Line
import io.github.nosorae.aiopic.engine.coach.Material
import io.github.nosorae.aiopic.engine.coach.MaterialKind
import io.github.nosorae.aiopic.engine.coach.Modality
import io.github.nosorae.aiopic.engine.coach.Speaker
import io.github.nosorae.aiopic.engine.coach.TALK_WARNING
import io.github.nosorae.aiopic.engine.coach.TokenUsage
import io.github.nosorae.aiopic.engine.coach.TransportEvent
import io.github.nosorae.aiopic.engine.coach.canRehearseAgain
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageGatheringDots
import io.github.nosorae.aiopic.ui.design.MontageIconButton
import io.github.nosorae.aiopic.ui.design.MontageListCell
import io.github.nosorae.aiopic.ui.design.MontageRadius
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageStepTrail
import io.github.nosorae.aiopic.ui.design.MontageTextField
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.design.MontageVoiceRibbon
import io.github.nosorae.aiopic.ui.design.icon_normal_close
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Instant
import io.github.nosorae.aiopic.ui.design.Res as DesignRes

/**
 * 대화 화면의 바깥 겹. 동의가 있으면 마이크 권한을 받고 세션을 연다. 앱이 뒤로 가면 멈추고 돌아오면 이어 간다(F11.8).
 * [onExit] 는 화면을 닫는다. 세션은 저장돼 있어 다시 들어오면 이어 간다. 세션이 끝나 스크립트 단계로 가면(30분, 피드백 끝, 리허설 없이 넘김) [onFinish] 로 스크립트 화면을 연다(F14.1).
 */
@Composable
fun LiveCoachRoute(viewModel: LiveCoachViewModel, onExit: () -> Unit, modifier: Modifier = Modifier, onFinish: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val finished = state.coach?.status == CoachStatus.Finished
    val currentOnFinish by rememberUpdatedState(onFinish)
    LaunchedEffect(finished) { if (finished) currentOnFinish() }
    val requestMic = rememberMicPermission()
    LaunchedEffect(state.consented) {
        if (state.consented == true) viewModel.onIntent(if (requestMic()) LiveCoachIntent.Connect else LiveCoachIntent.MicDenied)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(LiveCoachIntent.LeaveApp) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(LiveCoachIntent.ReturnToApp) }
    LiveCoachScreen(state, onIntent = viewModel::onIntent, onExit = onExit, onOpenSettings = rememberOpenAppSettings(), modifier = modifier)
}

/**
 * 코치 대화 화면(F11, F12). 위에서부터 단계 표시, 세션 비용, 안내(끊김, 25분, 멈춤), 소재 메모 네 칸, 표현 카드, 받아쓰기다.
 * 받아쓰기는 말풍선 없이 위아래로 쌓고, 지금 말하는 쪽의 목소리 띠는 아래 버튼 위에 둔다. 메모와 카드는 눌러서 고친다.
 * 리허설 단계는 받아쓰기 대신 펼친 메모를 보이고 아래쪽이 녹음 조작으로 바뀐다(F13.1). 스크립트 화면(#267)은 아직 없어서 정리 중 안내만 보인다.
 */
@Composable
fun LiveCoachScreen(state: LiveCoachUiState, onIntent: (LiveCoachIntent) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    val coach = state.coach
    var editing by remember { mutableStateOf<Edit?>(null) }
    editing?.let { edit ->
        EditSheet(
            title = stringResource(edit.title),
            initial = edit.text,
            onSave = {
                onIntent(edit.intent(it))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    MontageScreen(
        modifier = modifier,
        topBar = {
            MontageTopBar(title = stringResource(Res.string.live_title)) {
                MontageIconButton(icon = DesignRes.drawable.icon_normal_close, contentDescription = stringResource(Res.string.live_exit), onClick = onExit)
            }
        },
        bottomBar = {
            when {
                coach == null || coach.status == CoachStatus.Finished -> Unit
                coach.phase == CoachPhase.Rehearse -> RehearsalControls(state.rehearsal, onIntent)
                else -> TalkControls(state, coach, onIntent)
            }
        },
    ) {
        when {
            state.consented == false -> ConsentContent(onAgree = { onIntent(LiveCoachIntent.GiveConsent) })
            state.micDenied -> Notice(stringResource(Res.string.live_mic_denied), action = stringResource(Res.string.live_open_settings), onAction = onOpenSettings)
            coach == null -> if (state.consented == true) Waiting(stringResource(Res.string.live_connecting))
            coach.status == CoachStatus.Finished -> Waiting(stringResource(Res.string.live_script_wait))
            else -> Conversation(state.question, coach, onIntent, onEdit = { editing = it })
        }
    }
}

@Composable
private fun Conversation(question: String, coach: CoachState, onIntent: (LiveCoachIntent) -> Unit, onEdit: (Edit) -> Unit) {
    val list = rememberLazyListState()
    // 받아쓰기가 대화의 중심이라 처음 열면 맨 아래 최근 줄을 보인다. 끝을 보고 있을 때 새 말이 들어오면 따라 내려가고,
    // 위로 올려 메모를 읽는 중이면 끌어내리지 않는다.
    val atEnd = !list.canScrollForward
    val heard = coach.lines.size to coach.lines.lastOrNull()?.text?.length
    var seen by remember { mutableStateOf<Pair<Int, Int?>?>(null) }
    // 리허설은 받아쓰기를 숨기고 메모를 펼쳐 위에서부터 보인다.
    val rehearsing = coach.phase == CoachPhase.Rehearse
    LaunchedEffect(heard) {
        if (!rehearsing && (seen == null || (heard != seen && atEnd))) {
            val count = snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
            list.scrollToItem(count - 1)
        }
        seen = heard
    }
    var notesOpen by rememberSaveable(rehearsing) { mutableStateOf(rehearsing) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        PhaseHeader(coach.phase)
        QuestionText(question)
        // 안내는 받아쓰기를 따라 스크롤되지 않게 위에 고정한다. 끊겼을 때 "다시 연결" 이 늘 보여야 한다.
        val notice = when {
            coach.disconnected != null -> null
            coach.timeWarning -> Res.string.live_time_warning
            coach.status == CoachStatus.Paused -> Res.string.live_paused
            else -> null
        }
        coach.disconnected?.let { reason ->
            Notice(stringResource(droppedMessage(reason)), action = stringResource(Res.string.live_reconnect), onAction = { onIntent(LiveCoachIntent.Resume) })
        }
        notice?.let { Notice(stringResource(it)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
            item { CostLine(coach.costUsd) }
            item {
                val filled = MaterialKind.entries.count { kind -> coach.materials.any { it.kind == kind } }
                val state = stringResource(if (notesOpen) Res.string.live_notes_open else Res.string.live_notes_closed)
                MontageListCell(
                    title = stringResource(Res.string.live_notes),
                    caption = stringResource(Res.string.live_notes_summary, filled, MaterialKind.entries.size, coach.expressions.size),
                    chevron = true,
                    onClick = { notesOpen = !notesOpen },
                    modifier = Modifier.semantics { stateDescription = state },
                )
            }
            if (notesOpen) {
                item { SectionTitle(stringResource(Res.string.live_materials)) }
                itemsIndexed(MaterialKind.entries) { _, kind ->
                    val text = coach.materials.filter { it.kind == kind }.joinToString(", ") { it.text }
                    val title = kind.label
                    MontageListCell(
                        title = stringResource(title),
                        caption = text.ifEmpty { stringResource(Res.string.live_material_empty) },
                        chevron = true,
                        onClick = { onEdit(Edit(title, text) { LiveCoachIntent.EditMaterial(kind, it) }) },
                    )
                }
                item { SectionTitle(stringResource(Res.string.live_expressions)) }
                if (coach.expressions.isEmpty()) item { Body(stringResource(Res.string.live_expressions_empty)) }
                itemsIndexed(coach.expressions) { index, expression ->
                    ExpressionCard(expression, onClick = { onEdit(Edit(Res.string.live_expressions, expression.english) { LiveCoachIntent.EditExpression(index, it) }) })
                }
            }
            if (!rehearsing) {
                item { SectionTitle(stringResource(Res.string.live_transcript)) }
                if (coach.lines.isEmpty()) item { Body(stringResource(Res.string.live_transcript_empty)) }
                itemsIndexed(coach.lines) { _, line -> TranscriptLine(line) }
            }
            item { Spacer(Modifier.height(MontageSpacing.spacing8)) }
        }
    }
}

/** 지금 답을 만드는 질문. 길면 두 줄로 접고 눌러서 펼친다. */
@Composable
private fun QuestionText(question: String) {
    if (question.isBlank()) return
    var open by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val toggle = if (open || overflow) Modifier.clickable(role = Role.Button) { open = !open } else Modifier
    Text(
        question,
        style = MontageTheme.typography.body1.medium,
        color = MontageTheme.colors.foregroundNeutralPrimary,
        maxLines = if (open) Int.MAX_VALUE else QUESTION_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!open) overflow = it.hasVisualOverflow },
        modifier = Modifier.fillMaxWidth().then(toggle).padding(horizontal = Gutter, vertical = MontageSpacing.spacing8),
    )
}

/** 네 단계 가운데 지금 단계. 스크립트 정리는 네 단계를 다 지난 것으로 그린다. */
@Composable
private fun PhaseHeader(phase: CoachPhase) {
    val step = minOf(phase.ordinal + 1, TALK_PHASES)
    Column(Modifier.padding(horizontal = Gutter).padding(top = MontageSpacing.spacing8), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.live_phase_step, step, TALK_PHASES), style = MontageTheme.typography.label1.medium, color = MontageTheme.colors.foregroundNeutralSecondary)
            Text(stringResource(phase.label), style = MontageTheme.typography.heading2.bold, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.semantics { heading() })
        }
        MontageStepTrail(total = TALK_PHASES, done = step - 1)
    }
}

/** 세션 비용(F12.4). 누르면 어떻게 계산한 값인지 아래에 펼친다. */
@Composable
private fun CostLine(costUsd: Double) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { open = !open }
            .padding(horizontal = Gutter, vertical = MontageSpacing.spacing8),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4),
    ) {
        Text(stringResource(Res.string.live_cost, usd(costUsd)), style = MontageTheme.typography.label1.medium, color = MontageTheme.colors.foregroundNeutralSecondary)
        if (open) Text(stringResource(Res.string.live_cost_help), style = MontageTheme.typography.label1.regular, color = MontageTheme.colors.foregroundNeutralSecondary)
    }
}

/** 지금 말하는 쪽의 목소리 띠와 "잠깐 생각할게요", "다음 단계" 버튼. 연결이 열려 있을 때만 띠를 보인다. 피드백 단계에서 리허설이 두 번 미만이면 "한 번 더 리허설" 도 보인다(F13.3). */
@Composable
private fun TalkControls(state: LiveCoachUiState, coach: CoachState, onIntent: (LiveCoachIntent) -> Unit) {
    val talking = coach.status == CoachStatus.Active && coach.disconnected == null
    Column(Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing12), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        if (talking) {
            Text(
                stringResource(if (state.coachSpeaking) Res.string.live_coach_speaking else Res.string.live_listening),
                style = MontageTheme.typography.label1.medium,
                color = MontageTheme.colors.foregroundBrandPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            MontageVoiceRibbon(if (state.coachSpeaking) state.playbackLevel else state.micLevel)
        }
        if (coach.canRehearseAgain) {
            MontageButton(
                text = stringResource(Res.string.live_rehearse_again),
                onClick = { onIntent(RehearsalIntent.RehearseAgain) },
                modifier = Modifier.fillMaxWidth(),
                variant = MontageButtonVariant.Outlined,
                type = MontageButtonType.Assistive,
            )
        }
        val paused = coach.status == CoachStatus.Paused
        // 큰 글자에서는 나란히 두면 버튼 글이 잘려 위아래로 쌓는다.
        val stacked = LocalDensity.current.fontScale > 1.3f
        val buttons = @Composable { width: Modifier ->
            MontageButton(
                text = stringResource(if (paused) Res.string.live_resume else Res.string.live_pause),
                onClick = { onIntent(if (paused) LiveCoachIntent.Resume else LiveCoachIntent.Pause) },
                modifier = width,
                variant = MontageButtonVariant.Outlined,
                type = MontageButtonType.Assistive,
                enabled = coach.disconnected == null,
            )
            MontageButton(text = stringResource(Res.string.live_next_phase), onClick = { onIntent(LiveCoachIntent.NextPhase) }, modifier = width)
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) { buttons(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) { buttons(Modifier.weight(1f)) }
        }
    }
}

/** 말풍선 없이 누가 말했는지 한 줄, 한 말을 그 아래에 쌓는다(F12.1). */
@Composable
private fun TranscriptLine(line: Line) {
    val coach = line.speaker == Speaker.Coach
    Column(Modifier.padding(horizontal = Gutter).semantics(mergeDescendants = true) {}) {
        Text(
            stringResource(if (coach) Res.string.live_speaker_coach else Res.string.live_speaker_me),
            style = MontageTheme.typography.label1.bold,
            color = if (coach) MontageTheme.colors.foregroundBrandPrimary else MontageTheme.colors.foregroundNeutralSecondary,
        )
        Text(line.text.trim(), style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralPrimary)
    }
}

/** 표현 카드(F12.3). 영어 표현, 내가 한 말, 등급. 눌러서 영어 표현을 고친다. */
@Composable
private fun ExpressionCard(expression: Expression, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(MontageRadius.radius12))
            .background(MontageTheme.colors.surfaceNeutralSecondary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(MontageSpacing.spacing12),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
            Text(expression.english, style = MontageTheme.typography.body1.bold, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
            Text(expression.grade, style = MontageTheme.typography.label1.bold, color = MontageTheme.colors.foregroundBrandPrimary)
        }
        Text(stringResource(Res.string.live_expression_said, expression.userSaid), style = MontageTheme.typography.label1.regular, color = MontageTheme.colors.foregroundNeutralSecondary)
    }
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(MontageRadius.radius12))
            .background(MontageTheme.colors.surfaceNeutralSecondary)
            .padding(MontageSpacing.spacing12),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
    ) {
        Text(text, style = MontageTheme.typography.body2.medium, color = MontageTheme.colors.foregroundNeutralPrimary)
        if (action != null) {
            MontageButton(text = action, onClick = onAction, variant = MontageButtonVariant.Outlined, type = MontageButtonType.Assistive, size = MontageButtonSize.Medium)
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MontageGatheringDots(description = text)
        Text(text, style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.padding(top = MontageSpacing.spacing16))
    }
}

/** 첫 세션 전 실시간 음성 전송 동의(F15.1). 녹음 파일 전송 동의와 따로 받는다. */
@Composable
private fun ConsentContent(onAgree: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16)) {
        item { SectionTitle(stringResource(Res.string.live_consent_title)) }
        item { Body(stringResource(Res.string.live_consent_send)) }
        item { Body(stringResource(Res.string.live_consent_free_tier)) }
        item { Body(stringResource(Res.string.live_consent_eligibility)) }
        item {
            MontageButton(
                text = stringResource(Res.string.live_consent_agree),
                onClick = onAgree,
                modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MontageTheme.typography.headline2.bold,
        color = MontageTheme.colors.foregroundNeutralPrimary,
        modifier = Modifier.padding(horizontal = Gutter).padding(top = MontageSpacing.spacing16).semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = Gutter))
}

/** 한 칸을 고치는 시트. 저장하면 고친 글을 넘긴다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MontageTheme.colors.surfaceElevatedPrimary) {
        Column(Modifier.padding(horizontal = Gutter).padding(bottom = MontageSpacing.spacing16), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16)) {
            MontageTextField(value = text, onValueChange = { text = it }, title = title)
            MontageButton(
                text = stringResource(Res.string.live_edit_save),
                onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onSave(text) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 고치는 칸의 이름, 지금 글, 고친 글로 낼 일. */
private class Edit(val title: StringResource, val text: String, val intent: (String) -> LiveCoachIntent)

private val CoachPhase.label: StringResource
    get() = when (this) {
        CoachPhase.Explore -> Res.string.live_phase_explore
        CoachPhase.Express -> Res.string.live_phase_express
        CoachPhase.Rehearse -> Res.string.live_phase_rehearse
        CoachPhase.Feedback -> Res.string.live_phase_feedback
        CoachPhase.Script -> Res.string.live_phase_script
    }

private val MaterialKind.label: StringResource
    get() = when (this) {
        MaterialKind.Place -> Res.string.live_material_place
        MaterialKind.Person -> Res.string.live_material_person
        MaterialKind.Event -> Res.string.live_material_event
        MaterialKind.Feeling -> Res.string.live_material_feeling
    }

private fun droppedMessage(reason: String): StringResource = when (reason) {
    TransportEvent.Disconnected.RATE_LIMITED -> Res.string.live_dropped_rate_limited
    TransportEvent.Disconnected.NO_KEY -> Res.string.live_dropped_no_key
    else -> Res.string.live_dropped_network
}

/** "$0.42" 처럼 센트까지. 공통 코드에는 숫자 서식 함수가 없어 직접 만든다. */
internal fun usd(amount: Double): String {
    val cents = (amount * 100).roundToInt()
    return "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** 경험 끌어내기, 표현 코칭, 리허설, 피드백. */
private const val TALK_PHASES = 4

private val Gutter = MontageSpacing.spacing20

/** 접은 질문 줄 수. */
private const val QUESTION_LINES = 2

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object LiveCoachSamples {
    private val explore = CoachState(
        id = "c1",
        questionId = "q1",
        targetGrade = "IH",
        startedAt = Instant.fromEpochSeconds(0),
        lines = listOf(
            Line(CoachPhase.Explore, Speaker.Coach, "Tell me about a trip you took recently. Where did you go?"),
            Line(CoachPhase.Explore, Speaker.User, "I went to Busan with my sister last summer."),
            Line(CoachPhase.Explore, Speaker.Coach, "Nice! What was the most memorable moment?"),
        ),
        materials = listOf(Material(MaterialKind.Place, "Busan, Haeundae beach"), Material(MaterialKind.Person, "my sister")),
        expressions = listOf(Expression("진짜 좋았어", "It was an absolute blast", "IH")),
        usage = mapOf(CoachPhase.Explore to TokenUsage(input = mapOf(Modality.Audio to 120_000, Modality.Text to 8_000), output = mapOf(Modality.Audio to 5_000))),
    )

    private const val QUESTION = "Tell me about a memorable trip you took recently. Where did you go, who did you go with, and what made the trip so special for you?"

    val exploring = LiveCoachUiState(question = QUESTION, consented = true, coach = explore, micLevel = 0.4f)
    val dropped = LiveCoachUiState(question = QUESTION, consented = true, coach = explore.copy(disconnected = TransportEvent.Disconnected.RATE_LIMITED))
    val timeWarning = LiveCoachUiState(question = QUESTION, consented = true, coach = explore.copy(phase = CoachPhase.Feedback, talkTime = TALK_WARNING), playbackLevel = 0.6f)
    val consent = LiveCoachUiState(consented = false)
    val finished = LiveCoachUiState(question = QUESTION, consented = true, coach = explore.copy(phase = CoachPhase.Script, status = CoachStatus.Finished))
}

@Preview
@Composable
private fun LiveCoachScreenPreview() {
    MontageTheme { LiveCoachScreen(LiveCoachSamples.exploring, onIntent = {}, onExit = {}) }
}
