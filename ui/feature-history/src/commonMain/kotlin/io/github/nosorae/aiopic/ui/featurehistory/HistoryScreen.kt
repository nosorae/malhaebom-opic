package io.github.nosorae.aiopic.ui.featurehistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.answer.model.AnswerAnalysis
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.engine.session.model.SessionGrade
import io.github.nosorae.aiopic.ui.design.MontageCelebration
import io.github.nosorae.aiopic.ui.design.MontageCelebrationSize
import io.github.nosorae.aiopic.ui.design.MontageChip
import io.github.nosorae.aiopic.ui.design.MontageChipVariant
import io.github.nosorae.aiopic.ui.design.MontageGatheringDots
import io.github.nosorae.aiopic.ui.design.MontageGradeScale
import io.github.nosorae.aiopic.ui.design.MontageListCell
import io.github.nosorae.aiopic.ui.design.MontageProgressBar
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import io.github.nosorae.aiopic.ui.design.montageItemMotion
import io.github.nosorae.aiopic.ui.featurelive.CoachStartButton
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenAnswer: (answerId: String) -> Unit,
    onOpenCoach: () -> Unit,
    onOpenScript: (HistoryScript) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(state = state, onOpenSession = onOpenSession, onOpenAnswer = onOpenAnswer, onOpenCoach = onOpenCoach, onIntent = viewModel::onIntent, modifier = modifier, onOpenScript = onOpenScript)
}

/** 기록 목록에서 볼 종류. 두 종류 넘게 있을 때만 고를 수 있고, 있는 종류만 칩으로 보인다. */
internal enum class HistoryFilter { All, Exam, Practice, Script }

private val HistoryEntry.filter: HistoryFilter
    get() = when (this) {
        is HistorySession -> HistoryFilter.Exam
        is HistoryPractice -> HistoryFilter.Practice
        is HistoryScript -> HistoryFilter.Script
    }

/**
 * 기록 탭(S13, F7.3, F7.4). 모의고사 회차와 한 문항 연습 답변을 한 목록에 최근 것부터 보인다. 회차는 등급과 분석 모델,
 * 연습은 문항 유형과 점수를 보이고 분석 중과 실패도 그 줄에 보인다. 모델이 섞인 회차는 표시한다(F5.7).
 * 회차를 누르면 [onOpenSession] 로 회차 리포트를, 연습을 누르면 [onOpenAnswer] 로 그 답변 리포트를, 코치 스크립트를 누르면 [onOpenScript] 로 스크립트 화면을 연다(F14.6).
 * 아래에 문항 유형별 약점 점수를 약한 것부터 막대와 함께 두고, 그 위에 코치 시작 버튼([onOpenCoach], F10.1)을 둔다. 지난번에 본 것보다 오른 점수는 처음 볼 때 점수 둘레에 작은 축하를 한다(#198).
 * 이 화면의 주인공 움직임은 하나다. 맨 위 등급 눈금에서 최근 회차 등급으로 구슬이 미끄러져 멈춘다. 분석 중인 연습 줄의 작은 물결만 기다리는 동안 계속 움직인다.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenAnswer: (answerId: String) -> Unit,
    onOpenCoach: () -> Unit,
    onIntent: (HistoryIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenScript: (HistoryScript) -> Unit = {},
) {
    val currentOnIntent by rememberUpdatedState(onIntent)
    // ponytail: 필터는 이 화면 안의 보기 상태라 ViewModel 대신 rememberSaveable 에 둔다. 다른 화면이 필터를 알아야 하면 UiState 로 옮긴다.
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.All) }
    MontageScreen(modifier = modifier, topBar = { MontageTopBar(title = stringResource(Res.string.history_title)) }) {
        when {
            state.loading -> Unit

            state.empty -> Body(stringResource(Res.string.history_empty))

            else -> LazyColumn(Modifier.fillMaxWidth()) {
                val kinds = state.entries.map { it.filter }.toSet()
                val both = kinds.size > 1
                val shown = if (both && (filter == HistoryFilter.All || filter in kinds)) filter else HistoryFilter.All
                if (shown in setOf(HistoryFilter.All, HistoryFilter.Exam) && state.sessions.any { it.session.grade != null }) {
                    item {
                        SectionTitle(stringResource(Res.string.history_grades))
                        LatestGrade(state)
                    }
                }
                if (state.entries.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(Res.string.history_list))
                        if (both) FilterRow(listOf(HistoryFilter.All) + HistoryFilter.entries.filter { it in kinds }, shown, onSelect = { filter = it })
                    }
                }
                val entries = state.entries.filter { shown == HistoryFilter.All || it.filter == shown }
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is HistorySession -> SessionRow(entry, onClick = { onOpenSession(entry.session.id) }, modifier = montageItemMotion())

                        is HistoryPractice -> PracticeRow(entry, onClick = { onOpenAnswer(entry.answerId) }, modifier = montageItemMotion())

                        is HistoryScript -> MontageListCell(
                            title = stringResource(Res.string.history_script, entry.targetGrade),
                            caption = entry.question,
                            chevron = true,
                            onClick = { onOpenScript(entry) },
                            modifier = montageItemMotion().testTag("history-script"),
                        ) {
                            // 30분 대화를 잃지 않게 끝나지 않은 대화와 만들지 못한 스크립트도 줄로 남기고 누르면 이어 가거나 다시 만든다.
                            when {
                                !entry.finished -> Status(stringResource(Res.string.history_coach_resume), failed = false)
                                !entry.written -> Status(stringResource(Res.string.history_script_retry), failed = true)
                            }
                        }
                    }
                }
                if (state.weaknesses.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(Res.string.history_weakness))
                        Body(stringResource(Res.string.history_weakness_help))
                        CoachStartButton(onClick = onOpenCoach, modifier = Modifier.padding(horizontal = MontageSpacing.spacing20))
                    }
                }
                items(state.weaknesses, key = { it.typeName }) { weakness ->
                    MontageListCell(title = weakness.typeName, modifier = montageItemMotion()) {
                        MontageProgressBar(weakness.score / 100f, Modifier.width(96.dp).height(4.dp).align(Alignment.CenterVertically))
                        Box(Modifier.align(Alignment.CenterVertically)) {
                            Text(
                                text = stringResource(Res.string.history_score, weakness.score),
                                style = MontageTheme.typography.body2.bold,
                                color = MontageTheme.colors.foregroundNeutralPrimary,
                            )
                            // 지난번에 본 것보다 오른 점수를 처음 볼 때 점수 둘레에 작은 축하를 한다(#198). 줄이 화면에 들어올 때 터지고, 그때 본 표시를 남긴다.
                            if (weakness.rose) {
                                LaunchedEffect(weakness.score) { currentOnIntent(HistoryIntent.MarkWeaknessSeen(weakness)) }
                                MontageCelebration(
                                    MontageCelebrationSize.Small,
                                    Modifier.matchParentSize().wrapContentSize(unbounded = true).requiredSize(MontageSpacing.spacing48 * 2),
                                    playOnceKey = "history-weakness:${weakness.typeName}:${weakness.score}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val HistoryEntry.key: String
    get() = when (this) {
        is HistorySession -> "session:${session.id}"
        is HistoryPractice -> "practice:$answerId"
        is HistoryScript -> "script:$sessionId"
    }

@Composable
private fun FilterRow(filters: List<HistoryFilter>, selected: HistoryFilter, onSelect: (HistoryFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing4),
        horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8),
    ) {
        filters.forEach { filter ->
            MontageChip(
                text = stringResource(filter.label),
                onClick = { onSelect(filter) },
                active = filter == selected,
                variant = MontageChipVariant.Outlined,
                modifier = Modifier.testTag("history-filter-${filter.name.lowercase()}"),
            )
        }
    }
}

private val HistoryFilter.label: StringResource
    get() = when (this) {
        HistoryFilter.All -> Res.string.history_filter_all
        HistoryFilter.Exam -> Res.string.history_filter_exam
        HistoryFilter.Practice -> Res.string.history_filter_practice
        HistoryFilter.Script -> Res.string.history_filter_script
    }

@Composable
private fun SessionRow(row: HistorySession, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val models = row.models.joinToString(", ")
    MontageListCell(
        title = stringResource(if (row.session.status == ExamStatus.Abandoned) Res.string.history_session_abandoned else Res.string.history_session, row.number),
        caption = when {
            // 답한 문항 없이 끝난 회차는 분석할 것도 등급도 없다.
            row.answered == 0 && row.session.grade == null -> null

            row.models.isEmpty() -> stringResource(Res.string.history_models_none)

            row.mixedModels -> stringResource(Res.string.history_models_mixed, models)

            else -> stringResource(Res.string.history_models, models)
        },
        chevron = true,
        onClick = onClick,
        modifier = modifier.testTag("history-session"),
    ) {
        val grade = row.session.grade
        when {
            grade != null -> Text(grade.range, style = MontageTheme.typography.headline2.bold, color = MontageTheme.colors.foregroundNeutralStrong)
            row.session.gradeError != null -> Status(stringResource(Res.string.history_grade_failed), failed = true)
            row.answered == 0 -> Unit
            row.analyzed < row.answered -> Status(stringResource(Res.string.history_grade_waiting, row.analyzed, row.answered))
            else -> Status(stringResource(Res.string.history_grade_pending))
        }
    }
}

/**
 * 연습 한 줄. 제목에 "연습"을 붙여 회차 줄과 구분하고, 오른쪽에 점수를 둔다.
 * 기다리는 동안은 작은 물결과 함께 분석 작업 표(#205)가 말하는 지금 일(차례, 분석 중, 연결 기다림, 다시 보냄)과 무엇이 나오는지를 보인다.
 */
@Composable
private fun PracticeRow(row: HistoryPractice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val analysis = row.analysis
    MontageListCell(
        title = stringResource(Res.string.history_practice, row.typeName),
        caption = when (analysis) {
            AnswerAnalysis.Pending -> stringResource(
                when (row.job) {
                    JobState.Running -> Res.string.history_practice_running
                    JobState.WaitingNetwork -> Res.string.history_practice_waiting_network
                    JobState.RetryScheduled -> Res.string.history_practice_retry
                    else -> Res.string.history_practice_queued
                },
            )

            is AnswerAnalysis.Failed -> stringResource(Res.string.history_practice_failed_caption)

            // 말소리가 없어 보내지 않은 답은 분석한 모델이 없다(#241).
            is AnswerAnalysis.Done, is AnswerAnalysis.Unscorable -> row.model?.let { stringResource(Res.string.history_models, it) } ?: stringResource(Res.string.history_practice_silent)
        },
        chevron = true,
        onClick = onClick,
        modifier = modifier.testTag("history-practice"),
    ) {
        when (analysis) {
            AnswerAnalysis.Pending -> {
                val waiting = stringResource(Res.string.history_practice_pending)
                MontageGatheringDots(waiting, Modifier.width(48.dp).height(24.dp))
            }

            is AnswerAnalysis.Failed -> Status(stringResource(Res.string.history_practice_failed), failed = true)

            is AnswerAnalysis.Unscorable -> Status(stringResource(Res.string.history_practice_unscorable))

            is AnswerAnalysis.Done -> Text(
                text = stringResource(Res.string.history_score, analysis.feedback.score),
                style = MontageTheme.typography.headline2.bold,
                color = MontageTheme.colors.foregroundNeutralStrong,
            )
        }
    }
}

@Composable
private fun Status(text: String, failed: Boolean = false) {
    Text(
        text = text,
        style = MontageTheme.typography.label1.regular,
        color = if (failed) MontageTheme.colors.foregroundNegativePrimary else MontageTheme.colors.foregroundNeutralSecondary,
    )
}

/** 등급이 나온 가장 최근 회차를 등급 눈금에 놓는다. 등급 이름이 시험 팩 눈금에 없으면 그리지 않는다. 구슬은 회차와 등급마다 한 번만 움직인다. */
@Composable
private fun LatestGrade(state: HistoryUiState) {
    val latest = state.sessions.firstOrNull { it.session.grade != null }?.session ?: return
    val grade = latest.grade ?: return
    val names = state.grades
    if (grade.low !in names || grade.high !in names) return
    val label = stringResource(Res.string.history_latest, grade.range)
    Column {
        MontageGradeScale(
            names,
            names.indexOf(grade.low),
            names.indexOf(grade.high),
            description = label,
            modifier = Modifier.padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing8),
            playOnceKey = "history-grade:${latest.id}:${grade.range}",
        )
        Body(label)
    }
}

/** 회차 리포트와 같은 표기. 체크 결과가 한 등급으로 모이면 하나만 쓴다. */
private val SessionGrade.range: String get() = if (low == high) low else "$low ~ $high"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MontageTheme.typography.label1.bold,
        color = MontageTheme.colors.foregroundNeutralSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MontageSpacing.spacing20, end = MontageSpacing.spacing20, top = MontageSpacing.spacing24, bottom = MontageSpacing.spacing8)
            .semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MontageTheme.typography.body1Reading.regular,
        color = MontageTheme.colors.foregroundNeutralSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MontageSpacing.spacing20, vertical = MontageSpacing.spacing4),
    )
}

@Preview
@Composable
private fun HistoryScreenPreview() {
    MontageTheme { HistoryScreen(state = HistorySamples.filled, onOpenSession = {}, onOpenAnswer = {}, onOpenCoach = {}, onIntent = {}) }
}
