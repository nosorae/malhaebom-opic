package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageChip
import io.github.nosorae.aiopic.ui.design.MontageChipVariant
import io.github.nosorae.aiopic.ui.design.MontageListCell
import io.github.nosorae.aiopic.ui.design.MontageRadius
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.design.montageItemMotion
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** 표현 탭의 바깥 겹. [onOpenCard] 는 표현 상세를, [onStartReview] 는 복습 화면을, [onStartPractice] 는 말하기 연습 시작 시트를 연다. */
@Composable
fun ExpressionsRoute(
    viewModel: ExpressionsViewModel,
    onOpenCard: (Long) -> Unit,
    onStartReview: () -> Unit,
    modifier: Modifier = Modifier,
    onStartPractice: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ExpressionsScreen(state, viewModel::onIntent, onOpenCard, onStartReview, modifier, onStartPractice)
}

/** 표현 탭(S19, F18.1). 위에서부터 오늘 복습, 자주 틀리는 유형, 묶음 칩, 표현 목록이다. 목록이 비면 말하기 연습 시작 버튼을 둔다. */
@Composable
fun ExpressionsScreen(
    state: ExpressionsUiState,
    onIntent: (ExpressionsIntent) -> Unit,
    onOpenCard: (Long) -> Unit,
    onStartReview: () -> Unit,
    modifier: Modifier = Modifier,
    onStartPractice: () -> Unit = {},
) {
    MontageScreen(modifier = modifier, topBar = { MontageTopBar(title = stringResource(Res.string.expressions_title)) }) {
        when {
            !state.loaded -> Unit

            state.cards.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = MontageSpacing.spacing24),
                verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing16),
            ) {
                Body(stringResource(Res.string.expressions_empty))
                MontageButton(text = stringResource(Res.string.expressions_start_practice), onClick = onStartPractice, modifier = Modifier.fillMaxWidth())
            }

            else -> LazyColumn(Modifier.fillMaxWidth()) {
                item { TodayReview(state, onStartReview) }
                if (state.errorCounts.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(Res.string.expressions_error_types))
                        state.errorCounts.forEach { (type, count) ->
                            MontageListCell(title = stringResource(type.label)) {
                                Text(stringResource(Res.string.expressions_error_count, count), style = MontageTheme.typography.body2.bold, color = MontageTheme.colors.foregroundNeutralPrimary)
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = MontageSpacing.spacing12),
                        horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
                    ) {
                        ExpressionFilter.entries.forEach { filter ->
                            MontageChip(
                                text = stringResource(filter.label),
                                onClick = { onIntent(ExpressionsIntent.SelectFilter(filter)) },
                                active = filter == state.filter,
                                variant = MontageChipVariant.Outlined,
                            )
                        }
                    }
                }
                val shown = state.shown
                if (shown.isEmpty()) item { Body(stringResource(Res.string.expressions_empty_filter), Modifier.padding(horizontal = Gutter)) }
                items(shown, key = { it.id }) { card ->
                    MontageListCell(
                        title = card.better,
                        caption = "${card.meaning} · ${cardTag(card)}",
                        chevron = true,
                        onClick = { onOpenCard(card.id) },
                        modifier = montageItemMotion(),
                    )
                }
            }
        }
    }
}

/** 오늘 남은 장 수와 분, 이번 주 복습한 날 수, 시작 버튼. 할 카드가 없으면 버튼을 끈다. */
@Composable
private fun TodayReview(state: ExpressionsUiState, onStartReview: () -> Unit) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    Column(
        Modifier
            .padding(horizontal = Gutter, vertical = MontageSpacing.spacing12)
            .fillMaxWidth()
            .background(colors.surfaceNeutralSecondary, RoundedCornerShape(MontageRadius.radius16))
            .padding(MontageSpacing.spacing20),
        verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
    ) {
        Text(stringResource(Res.string.expressions_today), style = typography.headline1.bold, color = colors.foregroundNeutralPrimary)
        Text(
            text = if (state.todayCount > 0) stringResource(Res.string.expressions_today_left, state.todayCount, state.minutesLeft) else stringResource(Res.string.expressions_today_done),
            style = typography.body1.regular,
            color = colors.foregroundNeutralPrimary,
        )
        Text(stringResource(Res.string.expressions_week_days, state.weekDays), style = typography.body2.regular, color = colors.foregroundNeutralSecondary)
        MontageButton(
            text = stringResource(Res.string.expressions_start_review),
            onClick = onStartReview,
            enabled = state.todayCount > 0,
            modifier = Modifier.fillMaxWidth().padding(top = MontageSpacing.spacing8),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MontageTheme.typography.headline2.bold,
        color = MontageTheme.colors.foregroundNeutralPrimary,
        modifier = Modifier.padding(start = Gutter, end = Gutter, top = MontageSpacing.spacing20, bottom = MontageSpacing.spacing4),
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = modifier)
}

private val ExpressionFilter.label
    get() = when (this) {
        ExpressionFilter.All -> Res.string.filter_all
        ExpressionFilter.Archived -> Res.string.filter_archived
        else -> kind!!.label
    }

internal val Gutter = MontageSpacing.spacing20

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 카드와 상태. */
internal object ExpressionSamples {
    private val at = Instant.parse("2026-11-19T09:00:00Z")

    private fun card(id: Long, kind: CardKind, said: String, better: String, meaning: String, errorType: ErrorType? = null, firstSeen: Boolean = false) = ExpressionCard(id, kind, said, better, meaning, errorType, "주말에 한 일을 말하다가", createdAt = at, dueAt = at, firstSeen = firstSeen, reviewCount = 3, againCount = 1)

    val correction = card(1, CardKind.Correction, "I go to the park yesterday with my friend.", "I went to the park yesterday with my friend.", "어제 친구와 공원에 갔다.", ErrorType.Tense)
    val article = card(2, CardKind.Correction, "I have dog at home.", "I have a dog at home.", "집에 개가 한 마리 있다.", ErrorType.Article)
    val unknown = card(3, CardKind.UnknownExpression, "그건 좀 애매해요.", "It's kind of hard to say.", "그건 좀 애매해요.")
    val formulaic = card(4, CardKind.Formulaic, "I think it is good.", "To be honest, I'd say it's worth it.", "솔직히 말하면 그럴 만한 가치가 있다.")
    val natural = card(5, CardKind.MoreNatural, "It was very fun.", "It was a lot of fun.", "정말 재미있었다.")
    val firstSeen = natural.copy(id = 6, firstSeen = true, reviewCount = 0, againCount = 0)

    val tab = ExpressionsUiState(loaded = true, cards = listOf(correction, article, unknown, formulaic, natural), todayCount = 8, weekDays = 3)
    val emptyTab = ExpressionsUiState(loaded = true)
}

@Preview
@Composable
private fun ExpressionsScreenPreview() {
    MontageTheme { ExpressionsScreen(ExpressionSamples.tab, {}, {}, {}) }
}
