package io.github.nosorae.aiopic.ui.featurespeaking

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.speaking.Asked
import io.github.nosorae.aiopic.engine.speaking.CardStatus
import io.github.nosorae.aiopic.engine.speaking.OralReview
import io.github.nosorae.aiopic.engine.speaking.QuestionKind
import io.github.nosorae.aiopic.engine.speaking.ReviewValues
import io.github.nosorae.aiopic.engine.speaking.SpeakingRecord
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageChip
import io.github.nosorae.aiopic.ui.design.MontageGatheringDots
import io.github.nosorae.aiopic.ui.design.MontageListCell
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.featureexpressions.cardTag
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 연습 정리 화면의 바깥 겹. [onOpenCard] 는 표현 상세를 열고 [onDone] 은 연습 탭으로 돌아간다(F17.7). */
@Composable
fun SpeakingSummaryRoute(viewModel: SpeakingSummaryViewModel, onOpenCard: (Long) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SpeakingSummaryScreen(state, viewModel::onIntent, onOpenCard, onDone, modifier)
}

/**
 * 연습 정리 화면(S18, F17.6). 위에서부터 요약(대화 시간, 내가 말한 시간과 내 말 비율, 표현을 쓴 횟수, 만든 카드 수, 다룬 질문),
 * 말로 복습 결과(F17.10, 비었으면 숨김), 카드 목록이다. 말 속도와 쉼 횟수는 보이지 않는다.
 */
@Composable
fun SpeakingSummaryScreen(state: SpeakingSummaryUiState, onIntent: (SpeakingSummaryIntent) -> Unit, onOpenCard: (Long) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    MontageScreen(
        modifier = modifier,
        topBar = { MontageTopBar(title = stringResource(Res.string.summary_title)) },
        bottomBar = { BottomBar(state.notice, onUndo = { onIntent(SpeakingSummaryIntent.Undo) }, onDone = onDone) },
    ) {
        val record = state.record ?: return@MontageScreen
        LazyColumn(Modifier.fillMaxSize()) {
            item { Summary(record, state.userPercent) }
            if (state.oral.isNotEmpty()) {
                item { SectionTitle(stringResource(Res.string.summary_oral)) }
                items(state.oral, key = { "oral-${it.review.cardId}" }) { row -> OralLine(row) { onIntent(SpeakingSummaryIntent.ToggleVerdict(row.review.cardId)) } }
            }
            item { SectionTitle(stringResource(Res.string.summary_expressions)) }
            when {
                record.cardStatus == CardStatus.Failed -> item {
                    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12)) {
                        Body(stringResource(Res.string.summary_failed))
                        MontageButton(stringResource(Res.string.summary_retry), onClick = { onIntent(SpeakingSummaryIntent.Retry) }, variant = MontageButtonVariant.Outlined, size = MontageButtonSize.Medium)
                    }
                }

                record.cardStatus != CardStatus.Done -> item {
                    Column(Modifier.padding(horizontal = Gutter)) {
                        MontageGatheringDots(description = stringResource(Res.string.summary_writing))
                        Body(stringResource(Res.string.summary_writing))
                    }
                }

                state.cards.isEmpty() -> item { Body(stringResource(Res.string.summary_no_cards), Modifier.padding(horizontal = Gutter)) }

                else -> {
                    item { Body(stringResource(Res.string.summary_cards_hint), Modifier.padding(horizontal = Gutter).padding(bottom = MontageSpacing.spacing8)) }
                    items(state.cards, key = { it.id }) { card ->
                        CardLine(
                            card,
                            onOpen = { onOpenCard(card.id) },
                            onRemove = { onIntent(SpeakingSummaryIntent.Remove(card.id)) },
                            onReport = { onIntent(SpeakingSummaryIntent.Report(card.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(record: SpeakingRecord, userPercent: Int) {
    Column(Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing16), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        Value(stringResource(Res.string.summary_talk), duration(record.talkTime))
        Value(stringResource(Res.string.summary_my_talk), stringResource(Res.string.summary_my_talk_value, duration(record.userTalk), userPercent))
        if (record.cardStatus == CardStatus.Done) {
            Value(stringResource(Res.string.summary_uses), stringResource(Res.string.summary_uses_value, record.expressionUses))
            Value(stringResource(Res.string.summary_cards), stringResource(Res.string.summary_cards_value, record.cardCount))
        }
        if (record.questions.isNotEmpty()) {
            Text(stringResource(Res.string.summary_questions), style = MontageTheme.typography.label1.bold, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = Modifier.padding(top = MontageSpacing.spacing8))
            record.questions.forEach { Text(it.text, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralPrimary) }
        }
    }
}

@Composable
private fun Value(label: String, value: String) {
    // 큰 글자에서 값이 길어지면 이름 옆에서 줄을 바꾼다. 둘이 겹치지 않게 이름에 남은 폭을 준다.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        Text(label, style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MontageTheme.typography.body1.bold, color = MontageTheme.colors.foregroundNeutralPrimary, textAlign = TextAlign.End)
    }
}

/** 더 나은 표현과 판정. 판정 칩을 누르면 바뀐다. 바꿀 수 없으면 칩을 끄고 그 이유를 작게 보인다(F17.10). */
@Composable
private fun OralLine(row: OralRow, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = MontageSpacing.spacing8), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
            Text(row.review.better, style = MontageTheme.typography.body1.medium, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.weight(1f))
            MontageChip(stringResource(if (row.review.correct) Res.string.summary_correct else Res.string.summary_again), onClick = onToggle, active = row.review.correct, enabled = !row.locked)
        }
        Text(
            stringResource(if (row.locked) Res.string.summary_locked else Res.string.summary_change_verdict),
            style = MontageTheme.typography.label1.regular,
            color = MontageTheme.colors.foregroundNeutralSecondary,
        )
    }
}

/**
 * 카드 한 줄. 왼쪽으로 밀면 빼고, 길게 누르면 틀린 지적으로 신고하고, 누르면 표현 상세를 연다(F17.7).
 * 밀기와 길게 누르기는 눈에 보이지 않아서 화면 읽기 도구에는 같은 일을 하는 동작 둘을 단다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardLine(card: ExpressionCard, onOpen: () -> Unit, onRemove: () -> Unit, onReport: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val remove = stringResource(Res.string.summary_remove_action)
    val report = stringResource(Res.string.summary_report_action)
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { Row(Modifier.fillMaxSize().background(MontageTheme.colors.foregroundNegativePrimaryOpacity12)) {} },
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MontageTheme.colors.backgroundNeutralPrimary)
                .combinedClickable(onClick = onOpen, onLongClick = onReport)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(remove) { onRemove().let { true } },
                        CustomAccessibilityAction(report) { onReport().let { true } },
                    )
                }
                .padding(horizontal = Gutter, vertical = MontageSpacing.spacing12),
            verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4),
        ) {
            Text(card.said, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralSecondary)
            Text(card.better, style = MontageTheme.typography.body1.bold, color = MontageTheme.colors.foregroundNeutralPrimary)
            Text(cardTag(card), style = MontageTheme.typography.label1.medium, color = MontageTheme.colors.foregroundBrandPrimary)
        }
    }
}

/** 뺀 카드의 되돌리기(5초)와 "다 봤어요". */
@Composable
private fun BottomBar(notice: SummaryNotice?, onUndo: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing12), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        if (notice != null) {
            Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (notice is SummaryNotice.Removed) Res.string.summary_removed else Res.string.summary_reported),
                    style = MontageTheme.typography.body2.regular,
                    color = MontageTheme.colors.foregroundNeutralPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (notice is SummaryNotice.Removed) MontageButton(stringResource(Res.string.summary_undo), onClick = onUndo, variant = MontageButtonVariant.Text, size = MontageButtonSize.Medium)
            }
        }
        MontageButton(stringResource(Res.string.summary_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MontageTheme.typography.headline2.bold,
        color = MontageTheme.colors.foregroundNeutralPrimary,
        modifier = Modifier.padding(start = Gutter, end = Gutter, top = MontageSpacing.spacing20, bottom = MontageSpacing.spacing4).semantics { heading() },
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = modifier)
}

@Composable
private fun duration(value: Duration): String = stringResource(Res.string.summary_minutes_seconds, value.inWholeMinutes.toInt(), (value.inWholeSeconds % 60).toInt())

private val Gutter = MontageSpacing.spacing20

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object SpeakingSummarySamples {
    private val at = Instant.parse("2026-11-19T09:00:00Z")
    private val record = SpeakingRecord(
        id = "p1",
        startedAt = at,
        talkTime = 20.minutes,
        userTalk = 9.minutes + 12.seconds,
        coachTalk = 4.minutes + 3.seconds,
        questions = listOf(Asked("What did you cook last weekend?", QuestionKind.PastExperience, "cooking"), Asked("How do you usually plan your meals?", QuestionKind.Routine, "cooking")),
        oralReviews = emptyList(),
        expressionUses = 3,
        cardCount = 2,
        reportCount = 0,
        cardStatus = CardStatus.Done,
    )
    private fun card(id: Long, kind: CardKind, said: String, better: String, errorType: ErrorType? = null) = ExpressionCard(id, kind, said, better, "뜻", errorType, "요리 이야기", createdAt = at, dueAt = at, practiceId = "p1")

    private val oral = listOf(
        OralRow(OralReview(7, "I ended up ordering takeout.", correct = true, judgedAtMs = 0, before = ReviewValues(null, null, 0, null)), locked = false),
        OralRow(OralReview(8, "The thing is, I rarely cook.", correct = false, judgedAtMs = 0, before = ReviewValues(null, null, 0, null)), locked = true),
    )

    val done = SpeakingSummaryUiState(
        loaded = true,
        record = record,
        cards = listOf(card(1, CardKind.Correction, "I make kimchi stew yesterday.", "I made kimchi stew yesterday.", ErrorType.Tense), card(2, CardKind.MoreNatural, "It was very delicious.", "It was really good.")),
        oral = oral,
    )
    val writing = done.copy(record = record.copy(cardStatus = CardStatus.Running), cards = emptyList())
    val failed = done.copy(record = record.copy(cardStatus = CardStatus.Failed), cards = emptyList(), oral = emptyList())
    val empty = done.copy(record = record.copy(cardCount = 0), cards = emptyList(), oral = emptyList())
    val removed = done.copy(notice = SummaryNotice.Removed(done.cards.first()))
}

@Preview
@Composable
private fun SpeakingSummaryScreenPreview() {
    MontageTheme { SpeakingSummaryScreen(SpeakingSummarySamples.done, {}, {}, {}) }
}
