package io.github.nosorae.aiopic.ui.featurelive

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.ui.design.MontageBottomSheet
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageChip
import io.github.nosorae.aiopic.ui.design.MontageChipVariant
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** 연습 탭 질문 줄과 기록 탭 약점 유형에 두는 진입 버튼(F10.1). */
@Composable
fun CoachStartButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    MontageButton(
        text = stringResource(Res.string.live_coach_start),
        onClick = onClick,
        modifier = modifier,
        variant = MontageButtonVariant.Outlined,
        size = MontageButtonSize.Medium,
    )
}

/** 목표 등급 칩. 시작 시트와 설정 화면이 같이 쓴다(F10.2, F10.3). */
@Composable
fun TargetGradeChips(grades: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        grades.forEach { grade ->
            MontageChip(text = grade, onClick = { onSelect(grade) }, active = grade == selected, variant = MontageChipVariant.Outlined)
        }
    }
}

/**
 * 코치 시작 시트의 바깥 겹. 질문을 고르면 [onStart] 로 대화 화면을 연다. 목표 등급은 고르는 즉시 전역 값에 저장되고 고른 질문과 함께 넘긴다.
 * 끝나지 않은 세션을 이어 가면 [onResume] 으로 그 세션의 대화 화면을 연다(F11.8). 바깥을 누르거나 뒤로가기를 누르거나 낼 질문이 없으면 [onDismiss] 다.
 */
@Composable
fun CoachStartRoute(
    viewModel: CoachStartViewModel,
    onStart: (questionId: String, question: String, targetGrade: String) -> Unit,
    onResume: (sessionId: String, questionId: String, question: String, targetGrade: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state.navigation) {
        when (val navigation = state.navigation ?: return@LaunchedEffect) {
            is CoachStartDestination.Start -> currentOnStart(navigation.question.id, navigation.question.text, navigation.targetGrade)
            CoachStartDestination.Close -> currentOnDismiss()
        }
        viewModel.onIntent(CoachStartIntent.NavigationHandled)
    }
    CoachStartSheet(state, onIntent = viewModel::onIntent, onResume = { onResume(it.id, it.questionId, it.question, it.targetGrade) }, onDismiss = onDismiss)
}

/**
 * 코치 시작 시트(F10.2~F10.5). 목표 등급, 이어폰 권장 한 줄, 예상 시간, 시작 버튼이다.
 * 끝내지 못한 대화가 있으면 그 질문과 "이어서 하기", "새 질문으로 시작" 을 보인다(F11.8).
 */
@Composable
fun CoachStartSheet(state: CoachStartUiState, onIntent: (CoachStartIntent) -> Unit, onResume: (CoachState) -> Unit, onDismiss: () -> Unit) {
    MontageBottomSheet(onDismissRequest = onDismiss) {
        CoachStartContent(state, onSelectGrade = { onIntent(CoachStartIntent.SelectTargetGrade(it)) }, onStart = { onIntent(CoachStartIntent.Start) }, onResume = onResume)
    }
}

@Composable
private fun CoachStartContent(state: CoachStartUiState, onSelectGrade: (String) -> Unit, onStart: () -> Unit, onResume: (CoachState) -> Unit) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    // 큰 글자에서 안내가 길어져도 시작 버튼은 스크롤 밖 아래에 늘 보인다.
    Column(Modifier.padding(horizontal = MontageSpacing.spacing20).padding(bottom = MontageSpacing.spacing16)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
            Text(
                stringResource(Res.string.live_sheet_title),
                style = typography.heading2.bold,
                color = colors.foregroundNeutralPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(Res.string.live_sheet_body), style = typography.body2.regular, color = colors.foregroundNeutralSecondary)
            Text(
                stringResource(Res.string.live_target_grade),
                style = typography.label1.bold,
                color = colors.foregroundNeutralSecondary,
                modifier = Modifier.padding(top = MontageSpacing.spacing16),
            )
            TargetGradeChips(state.grades, state.selected, onSelectGrade)
            Text(stringResource(Res.string.live_target_grade_help), style = typography.label1.regular, color = colors.foregroundNeutralSecondary)
            Text(
                stringResource(Res.string.live_sheet_earphones),
                style = typography.body2.regular,
                color = colors.foregroundNeutralPrimary,
                modifier = Modifier.padding(top = MontageSpacing.spacing16),
            )
            Text(stringResource(Res.string.live_sheet_duration), style = typography.body2.regular, color = colors.foregroundNeutralPrimary)
            state.unfinished?.let { session ->
                Text(
                    stringResource(Res.string.live_sheet_unfinished),
                    style = typography.label1.bold,
                    color = colors.foregroundNeutralSecondary,
                    modifier = Modifier.padding(top = MontageSpacing.spacing16),
                )
                Text(session.question, style = typography.body2.medium, color = colors.foregroundNeutralPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val unfinished = state.unfinished
        if (unfinished != null) {
            MontageButton(
                text = stringResource(Res.string.live_sheet_resume),
                onClick = { onResume(unfinished) },
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth().padding(top = MontageSpacing.spacing16),
            )
        }
        MontageButton(
            text = stringResource(if (unfinished != null) Res.string.live_sheet_restart else Res.string.live_sheet_start),
            onClick = onStart,
            enabled = state.selected != null,
            loading = state.starting,
            modifier = Modifier.fillMaxWidth().padding(top = if (unfinished != null) MontageSpacing.spacing8 else MontageSpacing.spacing16),
            variant = if (unfinished != null) MontageButtonVariant.Outlined else MontageButtonVariant.Solid,
        )
    }
}

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object CoachStartSamples {
    val default = CoachStartUiState(grades = listOf("IM1", "IM2", "IM3", "IH", "AL"), selected = "IH")

    /** 끝내지 못한 대화가 있다(F11.8). */
    val unfinished = default.copy(unfinished = CoachState("c1", "q1", "Tell me about a memorable trip you took recently. Where did you go?", "IH", Instant.fromEpochSeconds(0)))
}

@Preview
@Composable
private fun CoachStartContentPreview() {
    MontageTheme { CoachStartContent(CoachStartSamples.unfinished, onSelectGrade = {}, onStart = {}, onResume = {}) }
}
