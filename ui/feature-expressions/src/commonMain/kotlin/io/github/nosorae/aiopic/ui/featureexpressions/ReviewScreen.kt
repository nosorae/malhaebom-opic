package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageChip
import io.github.nosorae.aiopic.ui.design.MontageChipVariant
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import org.jetbrains.compose.resources.stringResource

/** 복습 화면의 바깥 겹. [onExit] 는 끝내기로 나간다. */
@Composable
fun ReviewRoute(viewModel: ReviewViewModel, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReviewScreen(state, viewModel::onIntent, onExit, modifier)
}

/**
 * 복습(S21, F18.4~F18.8). 처음 봄 카드는 정답을 가리지 않고 다음 버튼 하나를 둔다. 그 밖은 종류별 앞면에서 정답 보기를 누르면
 * 더 나은 표현과 내가 한 말을 위아래로 보이고 다시와 알았음을 둔다. 평가 버튼을 길게 누르면 어려움과 쉬움이 더 나온다.
 * 묶음을 끝내면 같은 자리에 복습 끝을 보인다.
 */
@Composable
fun ReviewScreen(state: ReviewUiState, onIntent: (ReviewIntent) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val card = state.current
    MontageScreen(
        modifier = modifier,
        topBar = { MontageTopBar(title = stringResource(Res.string.review_title)) },
        bottomBar = { if (state.loaded) Actions(state, card, onIntent, onExit) },
    ) {
        if (!state.loaded) return@MontageScreen
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = MontageSpacing.spacing16),
            verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16),
        ) {
            when {
                card != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                        Text(
                            stringResource(Res.string.review_progress, state.results.size + 1, state.total),
                            style = MontageTheme.typography.body2.bold,
                            color = MontageTheme.colors.foregroundNeutralSecondary,
                            modifier = Modifier.weight(1f).padding(top = MontageSpacing.spacing8),
                        )
                        MontageChip(
                            text = stringResource(Res.string.review_silent),
                            onClick = { onIntent(ReviewIntent.SetSilent(!state.silent)) },
                            active = state.silent,
                            variant = MontageChipVariant.Outlined,
                        )
                    }
                    if (card.firstSeen || state.revealed) Answer(card, state.silent, prompt = card.firstSeen) else Front(card, state.silent)
                }

                state.total == 0 -> Prompt(stringResource(Res.string.review_empty))

                else -> Done(state)
            }
        }
    }
}

/** 정답을 가린 앞면(F18.4). 몰랐던 표현과 정형 표현은 뜻과 상황, 고칠 표현은 빈칸으로 가린 내가 한 말과 틀린 유형, 더 자연스럽게는 내가 한 말이다. */
@Composable
private fun Front(card: ExpressionCard, silent: Boolean) {
    val typography = MontageTheme.typography
    when (card.kind) {
        CardKind.UnknownExpression, CardKind.Formulaic -> {
            Sentence(card.meaning)
            Caption(card.context)
            Prompt(stringResource(if (silent) Res.string.review_silent_prompt else Res.string.review_say_english))
        }

        CardKind.Correction -> {
            Sentence(maskedSaid(diffWords(card.said, card.better)))
            card.errorType?.let { Caption(stringResource(it.label)) }
            Prompt(stringResource(if (silent) Res.string.review_silent_prompt else Res.string.review_fix_sentence))
        }

        CardKind.MoreNatural -> {
            Text(card.said, style = typography.title3.bold, color = MontageTheme.colors.foregroundNeutralPrimary)
            Prompt(stringResource(if (silent) Res.string.review_silent_prompt else Res.string.review_more_natural))
        }
    }
}

/** 더 나은 표현을 위에, 내가 한 말을 아래에 두고 다른 부분을 칠한다(F18.5). 처음 봄 카드는 따라 말하라는 문구를 붙인다(F18.4). */
@Composable
private fun Answer(card: ExpressionCard, silent: Boolean, prompt: Boolean) {
    val typography = MontageTheme.typography
    DiffText(card, better = true, style = typography.title3.bold)
    DiffText(card, better = false, style = typography.body1Reading.regular)
    Caption(card.meaning)
    if (prompt) Prompt(stringResource(if (silent) Res.string.review_silent_prompt else Res.string.review_repeat))
}

/** 복습 끝(F18.8). 알았음, 다시, 처음 본 장 수와 이번 주 며칠째인지. */
@Composable
private fun Done(state: ReviewUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16)) {
        Text(stringResource(Res.string.review_done_title), style = MontageTheme.typography.title3.bold, color = MontageTheme.colors.foregroundNeutralPrimary)
        Prompt(stringResource(Res.string.review_done_counts, state.knewCount, state.againCount, state.firstSeenCount))
        Prompt(stringResource(Res.string.review_done_week, state.weekDays))
    }
}

@Composable
private fun Actions(state: ReviewUiState, card: ExpressionCard?, onIntent: (ReviewIntent) -> Unit, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = MontageSpacing.spacing16),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
    ) {
        when {
            card == null -> {
                if (state.total > 0 && state.canDoMore) MontageButton(stringResource(Res.string.review_more), onClick = { onIntent(ReviewIntent.MoreCards) }, modifier = Modifier.fillMaxWidth())
                MontageButton(stringResource(Res.string.review_close), onClick = onExit, type = MontageButtonType.Assistive, modifier = Modifier.fillMaxWidth())
            }

            card.firstSeen -> MontageButton(stringResource(Res.string.review_next), onClick = { onIntent(ReviewIntent.Next) }, modifier = Modifier.fillMaxWidth())

            !state.revealed -> MontageButton(stringResource(Res.string.review_reveal), onClick = { onIntent(ReviewIntent.Reveal) }, modifier = Modifier.fillMaxWidth())

            else -> {
                val ratings = if (state.allRatings) Rating.entries else listOf(Rating.Again, Rating.Good)
                Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                    ratings.forEach { rating ->
                        MontageButton(
                            text = stringResource(rating.label),
                            onClick = { onIntent(ReviewIntent.Rate(rating)) },
                            onLongClick = { onIntent(ReviewIntent.ShowAllRatings) },
                            type = if (rating == Rating.Again) MontageButtonType.Assistive else MontageButtonType.Primary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (!state.allRatings) {
                    MontageButton(
                        text = stringResource(Res.string.review_long_press),
                        onClick = { onIntent(ReviewIntent.ShowAllRatings) },
                        variant = MontageButtonVariant.Text,
                        type = MontageButtonType.Assistive,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private val Rating.label
    get() = when (this) {
        Rating.Again -> Res.string.review_again
        Rating.Hard -> Res.string.review_hard
        Rating.Good -> Res.string.review_good
        Rating.Easy -> Res.string.review_easy
    }

@Composable
private fun Sentence(text: String) {
    Text(text, style = MontageTheme.typography.title3.bold, color = MontageTheme.colors.foregroundNeutralPrimary)
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralSecondary)
}

@Composable
private fun Prompt(text: String) {
    Text(text, style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralPrimary)
}

internal object ReviewSamples {
    private val batch = listOf(ExpressionSamples.correction, ExpressionSamples.unknown, ExpressionSamples.natural)
    val firstSeen = ReviewUiState(loaded = true, remaining = listOf(ExpressionSamples.firstSeen) + batch)
    val correctionFront = ReviewUiState(loaded = true, remaining = batch, results = listOf(null))
    val unknownFront = ReviewUiState(loaded = true, remaining = batch.drop(1), results = listOf(null, Rating.Good))
    val naturalFront = ReviewUiState(loaded = true, remaining = batch.drop(2), results = listOf(null, Rating.Good, Rating.Again), silent = true)
    val revealed = correctionFront.copy(revealed = true)
    val allRatings = revealed.copy(allRatings = true)
    val done = ReviewUiState(loaded = true, results = listOf(null, Rating.Good, Rating.Again, Rating.Easy, Rating.Good, Rating.Hard), todayCount = 4, weekDays = 3)
    val empty = ReviewUiState(loaded = true)
}

@Preview
@Composable
private fun ReviewScreenPreview() {
    MontageTheme { ReviewScreen(ReviewSamples.revealed, {}, {}) }
}
