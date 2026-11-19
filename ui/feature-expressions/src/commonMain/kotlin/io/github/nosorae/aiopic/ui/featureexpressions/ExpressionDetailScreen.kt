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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageConfirmDialog
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTextField
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** 표현 상세의 바깥 겹. 지우면 [onDeleted] 로 표현 탭에 돌아간다(F18.3). */
@Composable
fun ExpressionDetailRoute(viewModel: ExpressionDetailViewModel, onDeleted: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            onDeleted()
            viewModel.onIntent(ExpressionDetailIntent.NavigationHandled)
        }
    }
    ExpressionDetailScreen(state, viewModel::onIntent, modifier)
}

/**
 * 표현 상세(S20). 내가 한 말(바뀐 부분 밑줄), 더 나은 표현(바뀐 부분 강조)과 듣기, 한국어 뜻, 상황, 복습 기록을 보인다.
 * 아래에 고치기, 이미 알아요(보관한 카드에서는 숨김), 필요 없어요나 꺼내기, 삭제를 둔다.
 */
@Composable
fun ExpressionDetailScreen(state: ExpressionDetailUiState, onIntent: (ExpressionDetailIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    MontageScreen(modifier = modifier, topBar = { MontageTopBar(title = stringResource(Res.string.expressions_title)) }) {
        val detail = state.detail
        if (detail == null) {
            if (state.loaded) Text(stringResource(Res.string.detail_missing), style = typography.body1.regular, color = colors.foregroundNeutralSecondary, modifier = Modifier.padding(Gutter))
            return@MontageScreen
        }
        val card = detail.card
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = MontageSpacing.spacing16),
            verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing24),
        ) {
            Field(stringResource(Res.string.detail_said)) { DiffText(card, better = false, style = typography.body1Reading.regular) }
            if (state.editing) {
                MontageTextField(value = state.draftBetter, onValueChange = { onIntent(ExpressionDetailIntent.ChangeBetter(it)) }, title = stringResource(Res.string.detail_better), singleLine = false)
                MontageTextField(value = state.draftMeaning, onValueChange = { onIntent(ExpressionDetailIntent.ChangeMeaning(it)) }, title = stringResource(Res.string.detail_meaning), singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                    MontageButton(stringResource(Res.string.detail_cancel), onClick = { onIntent(ExpressionDetailIntent.CancelEdit) }, type = MontageButtonType.Assistive, modifier = Modifier.weight(1f))
                    MontageButton(stringResource(Res.string.detail_save), onClick = { onIntent(ExpressionDetailIntent.SaveEdit) }, enabled = state.canSave, modifier = Modifier.weight(1f))
                }
            } else {
                Field(stringResource(Res.string.detail_better)) {
                    DiffText(card, better = true, style = typography.headline1.medium)
                    MontageButton(
                        text = stringResource(Res.string.detail_listen),
                        onClick = { onIntent(ExpressionDetailIntent.Listen) },
                        variant = MontageButtonVariant.Outlined,
                        type = MontageButtonType.Assistive,
                        size = MontageButtonSize.Small,
                    )
                }
                Field(stringResource(Res.string.detail_meaning)) { Value(card.meaning) }
            }
            Field(stringResource(Res.string.detail_context)) { Value(card.context) }
            Field(stringResource(Res.string.detail_history)) {
                Value(stringResource(Res.string.detail_history_value, card.reviewCount, card.againCount, detail.nextReview.month.ordinal + 1, detail.nextReview.day))
                if (state.intervalExtended) Text(stringResource(Res.string.detail_interval_extended), style = typography.body2.medium, color = colors.foregroundBrandPrimary)
            }
            if (!state.editing) {
                Column(verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.canKnow) MontageButton(stringResource(Res.string.detail_know), onClick = { onIntent(ExpressionDetailIntent.Know) }, modifier = Modifier.fillMaxWidth())
                    MontageButton(
                        text = stringResource(if (card.archived) Res.string.detail_unarchive else Res.string.detail_archive),
                        onClick = { onIntent(ExpressionDetailIntent.ToggleArchive) },
                        type = MontageButtonType.Assistive,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                        MontageButton(stringResource(Res.string.detail_edit), onClick = { onIntent(ExpressionDetailIntent.StartEdit) }, variant = MontageButtonVariant.Text, type = MontageButtonType.Assistive)
                        MontageButton(stringResource(Res.string.detail_delete), onClick = { onIntent(ExpressionDetailIntent.AskDelete) }, variant = MontageButtonVariant.Text, type = MontageButtonType.Negative)
                    }
                }
            }
        }
    }
    if (state.confirmingDelete) {
        MontageConfirmDialog(
            title = stringResource(Res.string.detail_delete_title),
            message = stringResource(Res.string.detail_delete_message),
            confirm = stringResource(Res.string.detail_delete_confirm),
            cancel = stringResource(Res.string.detail_cancel),
            onConfirm = { onIntent(ExpressionDetailIntent.ConfirmDelete) },
            onCancel = { onIntent(ExpressionDetailIntent.CancelDelete) },
        )
    }
}

@Composable
private fun Field(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        Text(title, style = MontageTheme.typography.label1.bold, color = MontageTheme.colors.foregroundNeutralSecondary)
        content()
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = MontageTheme.typography.body1Reading.regular, color = MontageTheme.colors.foregroundNeutralPrimary)
}

internal object ExpressionDetailSamples {
    private val next = LocalDate(2026, 11, 24)
    val detail = ExpressionDetailUiState(loaded = true, detail = ExpressionDetail(ExpressionSamples.correction, next))
    val known = detail.copy(intervalExtended = true)
    val archived = ExpressionDetailUiState(loaded = true, detail = ExpressionDetail(ExpressionSamples.natural.copy(archived = true), next))
    val editing = detail.copy(editing = true, draftBetter = ExpressionSamples.correction.better, draftMeaning = ExpressionSamples.correction.meaning)
}

@Preview
@Composable
private fun ExpressionDetailScreenPreview() {
    MontageTheme { ExpressionDetailScreen(ExpressionDetailSamples.detail, {}) }
}
