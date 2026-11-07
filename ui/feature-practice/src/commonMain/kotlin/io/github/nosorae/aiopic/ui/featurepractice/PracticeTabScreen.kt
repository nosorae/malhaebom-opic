package io.github.nosorae.aiopic.ui.featurepractice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.libs.haptics.api.HapticEffect
import io.github.nosorae.aiopic.ui.design.AnswerStep
import io.github.nosorae.aiopic.ui.design.LocalHaptics
import io.github.nosorae.aiopic.ui.design.MontageAnswerSteps
import io.github.nosorae.aiopic.ui.design.MontagePlayButton
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.featurelive.CoachStartButton
import org.jetbrains.compose.resources.stringResource

/**
 * 연습 탭의 바깥 겹. [onStart] 는 한 문항 연습 화면을 연다. 그 화면이 열리자마자 질문을 읽어서 Play 는 한 번만 누른다.
 * [onOpenCoach] 는 코치 시작 시트를 연다(F10.1).
 */
@Composable
fun PracticeTabRoute(viewModel: PracticeTabViewModel, onStart: () -> Unit, onOpenCoach: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PracticeTabScreen(state, onStart, onOpenCoach, modifier)
}

/**
 * 연습 탭의 안쪽 겹. 한 문항 연습 화면의 Play 전 모습과 같게 그려서, Play 를 누르면 같은 자리에서 질문 읽기가 이어지는 것처럼 보인다.
 * 위에서부터 단계 표시, 다음 문항을 낼 약한 유형, 코치 시작 버튼, Play, 규칙 한 줄이다.
 */
@Composable
fun PracticeTabScreen(state: PracticeTabUiState, onStart: () -> Unit, onOpenCoach: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    MontageScreen(modifier = modifier, topBar = { MontageTopBar(title = stringResource(Res.string.practice_tab_title)) }) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = MontageSpacing.spacing16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16),
        ) {
            MontageAnswerSteps(current = AnswerStep.Listen, withFeedback = true, modifier = Modifier.padding(horizontal = Gutter + MontageSpacing.spacing8))
            // 읽기 전에 "모든 유형" 을 보였다가 약한 유형으로 바꾸지 않는다.
            if (state.loaded) {
                WhyThisQuestion(state.weakness)
                CoachStartButton(onClick = onOpenCoach)
            }
            Spacer(Modifier.height(MontageSpacing.spacing16))
            MontagePlayButton(
                text = stringResource(Res.string.practice_play),
                busy = false,
                onClick = {
                    haptics.play(HapticEffect.Tap)
                    onStart()
                },
            )
            Text(
                text = stringResource(Res.string.practice_hint),
                style = MontageTheme.typography.body2.regular,
                color = MontageTheme.colors.foregroundNeutralSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Gutter),
            )
        }
    }
}

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object PracticeTabSamples {
    val weak = PracticeTabUiState(weakness = PracticeWeakness("롤플레이 해결", 42), loaded = true)
    val anyType = PracticeTabUiState(loaded = true)
}

@Preview
@Composable
private fun PracticeTabScreenPreview() {
    MontageTheme { PracticeTabScreen(state = PracticeTabSamples.weak, onStart = {}, onOpenCoach = {}) }
}
