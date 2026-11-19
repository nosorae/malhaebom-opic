package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageRadius
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * 연습 탭 맨 위 카드 둘의 바깥 겹(F16.1, F18.10). [onStartSpeaking] 은 연습 시작 시트를, [onOpenRunning] 은 도는 연습의 화면을,
 * [onOpenReview] 는 복습 화면을 연다.
 */
@Composable
fun SpeakingCardsRoute(viewModel: SpeakingCardsViewModel, onStartSpeaking: () -> Unit, onOpenRunning: () -> Unit, onOpenReview: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SpeakingCards(state, onStartSpeaking, onOpenRunning, onOpenReview, modifier)
}

/**
 * 말하기 연습 카드와 오늘 복습 카드. 말하기 연습 카드는 기본 연습 시간과 지난 연습 날짜를 한 줄로 보이고, 연습이 도는 중이면 돌아가기를 보인다.
 * 오늘 복습 카드는 남은 장 수와 분을, 할 카드가 없으면 오늘 복습 끝과 이번 주 복습한 날 수를, 카드가 하나도 없으면 연습하면 표현이 쌓인다는 문구를 보인다.
 */
@Composable
fun SpeakingCards(state: SpeakingCardsUiState, onStartSpeaking: () -> Unit, onOpenRunning: () -> Unit, onOpenReview: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.loaded) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12)) {
        val last = state.lastPractice
        HomeCard(
            title = stringResource(Res.string.cards_speaking_title),
            line = when {
                state.running -> stringResource(Res.string.cards_speaking_running)
                last == null -> stringResource(Res.string.cards_speaking_first, state.defaultMinutes)
                else -> stringResource(Res.string.cards_speaking_line, state.defaultMinutes, last.month.ordinal + 1, last.day)
            },
            button = stringResource(if (state.running) Res.string.cards_speaking_back else Res.string.cards_speaking_start),
            onClick = if (state.running) onOpenRunning else onStartSpeaking,
        )
        HomeCard(
            title = stringResource(Res.string.cards_review_title),
            line = when {
                !state.hasCards -> stringResource(Res.string.cards_review_empty)
                state.todayCount == 0 -> stringResource(Res.string.cards_review_done, state.weekDays)
                else -> stringResource(Res.string.cards_review_left, state.todayCount, state.minutesLeft)
            },
            button = stringResource(Res.string.cards_review_start).takeIf { state.todayCount > 0 },
            onClick = onOpenReview,
            variant = MontageButtonVariant.Outlined,
        )
    }
}

/** 눌러서 들어가는 단위라 카드로 묶는다. 버튼이 없으면 글만 보인다. */
@Composable
private fun HomeCard(title: String, line: String, button: String?, onClick: () -> Unit, variant: MontageButtonVariant = MontageButtonVariant.Solid) {
    val colors = MontageTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceNeutralSecondary, RoundedCornerShape(MontageRadius.radius16)).padding(MontageSpacing.spacing20),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
    ) {
        Text(title, style = MontageTheme.typography.headline1.bold, color = colors.foregroundNeutralPrimary, modifier = Modifier.semantics { heading() })
        Text(line, style = MontageTheme.typography.body1.regular, color = colors.foregroundNeutralPrimary)
        if (button != null) MontageButton(text = button, onClick = onClick, variant = variant, modifier = Modifier.fillMaxWidth().padding(top = MontageSpacing.spacing8))
    }
}

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
object SpeakingCardsSamples {
    val default = SpeakingCardsUiState(loaded = true, defaultMinutes = 20, lastPractice = LocalDate(2026, 11, 18), todayCount = 8, weekDays = 3, hasCards = true)
    val running = default.copy(running = true)
    val reviewDone = default.copy(todayCount = 0)
    val first = SpeakingCardsUiState(loaded = true, defaultMinutes = 20)
}

@Preview
@Composable
private fun SpeakingCardsPreview() {
    MontageTheme { SpeakingCards(SpeakingCardsSamples.default, {}, {}, {}) }
}
