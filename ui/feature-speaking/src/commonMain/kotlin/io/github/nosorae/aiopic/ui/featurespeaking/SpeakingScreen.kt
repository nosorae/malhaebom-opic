package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.Line
import io.github.nosorae.aiopic.engine.speaking.MAX_RECONNECTS
import io.github.nosorae.aiopic.engine.speaking.Speaker
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStatus
import io.github.nosorae.aiopic.engine.speaking.Usage
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageIconButton
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.design.MontageVoiceRibbon
import io.github.nosorae.aiopic.ui.design.icon_normal_close
import io.github.nosorae.aiopic.ui.featurelive.usd
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import io.github.nosorae.aiopic.ui.design.Res as DesignRes

/**
 * 연습 중 화면의 바깥 겹. 연습이 끝나면 [onFinish] 로 그 연습의 정리 화면으로 바꾸고, 도는 연습이 없으면 [onExit] 로 닫는다.
 * 닫거나 뒤로 가도 연습은 계속된다(F16.4).
 */
@Composable
fun SpeakingRoute(viewModel: SpeakingViewModel, onExit: () -> Unit, onFinish: (practiceId: String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnFinish by rememberUpdatedState(onFinish)
    LaunchedEffect(state.navigation) {
        when (val navigation = state.navigation ?: return@LaunchedEffect) {
            is SpeakingDestination.Summary -> currentOnFinish(navigation.practiceId)
            SpeakingDestination.Exit -> currentOnExit()
        }
        viewModel.onIntent(SpeakingIntent.NavigationHandled)
    }
    SpeakingScreen(state, viewModel::onIntent, onExit, modifier)
}

/**
 * 연습 중 화면(F16.18). 큰 상태 원(상태와 남은 분), 목소리 띠, 저장한 표현 수, "화면을 꺼도 계속돼요", 기본으로 접힌 대화 보기, 구석의 작은 추정 비용,
 * 젖은 손으로도 누를 수 있는 큰 일시정지와 끝내기 버튼이다. 탭은 없다.
 */
@Composable
fun SpeakingScreen(state: SpeakingUiState, onIntent: (SpeakingIntent) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val practice = state.practice
    MontageScreen(
        modifier = modifier,
        topBar = {
            MontageTopBar(title = stringResource(Res.string.running_title)) {
                MontageIconButton(icon = DesignRes.drawable.icon_normal_close, contentDescription = stringResource(Res.string.running_close), onClick = onExit)
            }
        },
        bottomBar = { if (practice != null && !practice.finished) Controls(practice) { onIntent(SpeakingIntent.Send(it)) } },
    ) {
        if (practice != null) Practice(practice, state.level)
    }
}

@Composable
private fun Practice(practice: SpeakingState, level: Float) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12),
    ) {
        val status = stringResource(practice.statusLabel)
        Box(
            Modifier.fillMaxWidth(CIRCLE_WIDTH).aspectRatio(1f).background(practice.circleColor(), CircleShape).semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4)) {
                Text(status, style = typography.heading1.bold, color = colors.foregroundNeutralPrimary, textAlign = TextAlign.Center)
                Text(stringResource(Res.string.running_left, practice.minutesLeft), style = typography.body1.medium, color = colors.foregroundNeutralPrimary)
            }
        }
        if (practice.status != SpeakingStatus.Paused && practice.reconnects == 0) MontageVoiceRibbon(level)
        Text(stringResource(Res.string.running_saved, practice.savedCount), style = typography.body1.medium, color = colors.foregroundNeutralPrimary)
        Text(stringResource(Res.string.running_screen_off), style = typography.body2.regular, color = colors.foregroundNeutralSecondary)
        val toggle = stringResource(if (open) Res.string.running_transcript_open else Res.string.running_transcript_closed)
        Text(
            stringResource(Res.string.running_transcript),
            style = typography.body2.medium,
            color = colors.foregroundBrandPrimary,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.semantics { stateDescription = toggle }.padding(vertical = MontageSpacing.spacing12),
        )
        if (open) practice.transcript.forEach { TranscriptLine(it) }
        Text(
            stringResource(Res.string.running_cost, usd(practice.usage.costUsd)),
            style = typography.label1.regular,
            color = colors.foregroundNeutralSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun TranscriptLine(line: Line) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing2)) {
        Text(stringResource(if (line.speaker == Speaker.User) Res.string.running_me else Res.string.running_coach_name), style = MontageTheme.typography.label1.bold, color = MontageTheme.colors.foregroundNeutralSecondary)
        Text(line.text, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralPrimary)
    }
}

/** 위아래로 쌓은 큰 버튼 둘. 끊김으로 멈췄으면 다시 시작이 다시 연결이다(F16.15). */
@Composable
private fun Controls(practice: SpeakingState, onAction: (PracticeCommand) -> Unit) {
    val paused = practice.status == SpeakingStatus.Paused
    Column(Modifier.padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing12), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12)) {
        MontageButton(
            text = stringResource(
                when {
                    practice.disconnected -> Res.string.running_reconnect
                    paused -> Res.string.running_resume
                    else -> Res.string.running_pause
                },
            ),
            onClick = { onAction(if (paused) PracticeCommand.Resume else PracticeCommand.Pause) },
            variant = MontageButtonVariant.Outlined,
            type = MontageButtonType.Assistive,
            modifier = Modifier.fillMaxWidth(),
        )
        MontageButton(text = stringResource(Res.string.running_finish), onClick = { onAction(PracticeCommand.Finish) }, modifier = Modifier.fillMaxWidth())
    }
}

/** 상태 원의 폭. 화면 폭의 60% 라서 작은 화면에서도 원 안 글자가 들어간다. */
private const val CIRCLE_WIDTH = 0.6f

private val SpeakingState.statusLabel
    get() = when {
        disconnected -> Res.string.running_dropped
        reconnects > 0 -> Res.string.running_reconnecting
        status == SpeakingStatus.Paused -> Res.string.running_paused
        status == SpeakingStatus.CoachSpeaking -> Res.string.running_coach
        else -> Res.string.running_listening
    }

@Composable
private fun SpeakingState.circleColor() = when {
    disconnected -> MontageTheme.colors.foregroundNegativePrimaryOpacity12
    status == SpeakingStatus.Paused || reconnects > 0 -> MontageTheme.colors.surfaceNeutralSecondary
    status == SpeakingStatus.CoachSpeaking -> MontageTheme.colors.surfaceBrandPrimaryOpacity22
    else -> MontageTheme.colors.surfaceBrandPrimaryOpacity12
}

/** 남은 분. 1초라도 남았으면 1분으로 올린다(F16.13). */
private val SpeakingState.minutesLeft: Int get() = ((remaining.inWholeSeconds.coerceAtLeast(0) + 59) / 60).toInt()

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object SpeakingSamples {
    private val listening = SpeakingState(
        id = "p1",
        startedAt = Instant.parse("2026-11-19T09:00:00Z"),
        planned = 20.minutes,
        route = AudioRoute.Earphones,
        topic = "cooking",
        talkTime = 7.minutes + 30.seconds,
        savedCount = 2,
        transcript = listOf(Line(Speaker.Coach, "What did you cook last weekend?"), Line(Speaker.User, "I made kimchi stew with my sister.")),
        usage = Usage(input = mapOf("AUDIO" to 14_000), output = mapOf("AUDIO" to 7_000)),
    )
    val running = SpeakingUiState(practice = listening, level = 0.5f)
    val coach = SpeakingUiState(practice = listening.copy(status = SpeakingStatus.CoachSpeaking), level = 0.5f)
    val paused = SpeakingUiState(practice = listening.copy(status = SpeakingStatus.Paused))
    val dropped = SpeakingUiState(practice = listening.copy(status = SpeakingStatus.Paused, reconnects = MAX_RECONNECTS + 1))
}

@Preview
@Composable
private fun SpeakingScreenPreview() {
    MontageTheme { SpeakingScreen(SpeakingSamples.running, {}, {}) }
}
