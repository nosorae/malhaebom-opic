package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nosorae.aiopic.engine.coach.CoachScript
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.ScriptLength
import io.github.nosorae.aiopic.engine.coach.ScriptParts
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.ui.design.MontageButton
import io.github.nosorae.aiopic.ui.design.MontageButtonSize
import io.github.nosorae.aiopic.ui.design.MontageButtonType
import io.github.nosorae.aiopic.ui.design.MontageButtonVariant
import io.github.nosorae.aiopic.ui.design.MontageGatheringDots
import io.github.nosorae.aiopic.ui.design.MontageScreen
import io.github.nosorae.aiopic.ui.design.MontageSpacing
import io.github.nosorae.aiopic.ui.design.MontageTextField
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.MontageTopBar
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 스크립트 화면의 바깥 겹. [onPractice] 는 지금 세 칸을 이은 글로 연습 녹음 화면을 연다(F14.5). */
@Composable
fun CoachScriptRoute(viewModel: CoachScriptViewModel, onPractice: (script: String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CoachScriptScreen(state, onIntent = viewModel::onIntent, onPractice = onPractice, modifier = modifier)
}

/**
 * 스크립트 화면(F14). 위에서부터 질문, 목표 등급 길이 안내, 도입, 본문, 마무리 세 칸, 원본 보기와 돌리기, 쓴 표현이다.
 * 세 칸은 바로 고치고 자동 저장한다. 아래에 "목표 등급에 맞게 다듬기" 와 "이 스크립트로 연습" 을 둔다.
 * 등급 이름과 길이는 받은 값을 그대로 그린다.
 */
@Composable
fun CoachScriptScreen(state: CoachScriptUiState, onIntent: (CoachScriptIntent) -> Unit, onPractice: (String) -> Unit, modifier: Modifier = Modifier) {
    val parts = state.parts
    MontageScreen(
        modifier = modifier,
        topBar = { MontageTopBar(title = stringResource(Res.string.script_title)) },
        bottomBar = { if (parts != null) ScriptActions(polishing = state.polishing, onPolish = { onIntent(CoachScriptIntent.Polish) }, onPractice = { onPractice(parts.text) }) },
    ) {
        when {
            state.writing -> Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                val text = stringResource(Res.string.script_writing)
                MontageGatheringDots(description = text)
                Text(text, style = MontageTheme.typography.body1.regular, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.padding(top = MontageSpacing.spacing16))
            }

            parts == null -> Column(Modifier.padding(vertical = MontageSpacing.spacing16), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                ScriptNotice(stringResource((state.error ?: AnalysisError.Rejected).message) + "\n" + stringResource(Res.string.script_transcript_kept))
                MontageButton(
                    text = stringResource(Res.string.script_retry),
                    onClick = { onIntent(CoachScriptIntent.Retry) },
                    modifier = Modifier.padding(horizontal = Gutter),
                    variant = MontageButtonVariant.Outlined,
                    type = MontageButtonType.Assistive,
                    size = MontageButtonSize.Medium,
                )
            }

            else -> ScriptContent(state, parts, onIntent)
        }
    }
}

@Composable
private fun ScriptContent(state: CoachScriptUiState, parts: ScriptParts, onIntent: (CoachScriptIntent) -> Unit) {
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = MontageSpacing.spacing16), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing12)) {
        if (state.question.isNotBlank()) Text(state.question, style = MontageTheme.typography.body1.medium, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.padding(horizontal = Gutter))
        state.length?.let { length ->
            Text(
                stringResource(Res.string.script_length, state.targetGrade, length.sentences.first, length.sentences.last, length.timeText()),
                style = MontageTheme.typography.label1.medium,
                color = MontageTheme.colors.foregroundBrandPrimary,
                modifier = Modifier.padding(horizontal = Gutter),
            )
        }
        state.error?.let { ScriptNotice(stringResource(it.message)) }
        ScriptPart.entries.forEach { part ->
            MontageTextField(
                value = parts[part],
                onValueChange = { onIntent(CoachScriptIntent.Edit(part, it)) },
                title = stringResource(part.label),
                enabled = !state.polishing,
                singleLine = false,
                modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth(),
            )
        }
        val original = state.script?.original
        if (state.edited && original != null) {
            Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
                MontageButton(
                    text = stringResource(if (showOriginal) Res.string.script_hide_original else Res.string.script_show_original),
                    onClick = { showOriginal = !showOriginal },
                    variant = MontageButtonVariant.Outlined,
                    type = MontageButtonType.Assistive,
                    size = MontageButtonSize.Medium,
                )
                MontageButton(
                    text = stringResource(Res.string.script_revert),
                    onClick = {
                        showOriginal = false
                        onIntent(CoachScriptIntent.Revert)
                    },
                    variant = MontageButtonVariant.Outlined,
                    type = MontageButtonType.Assistive,
                    size = MontageButtonSize.Medium,
                    enabled = !state.polishing,
                )
            }
            if (showOriginal) {
                Heading(stringResource(Res.string.script_original))
                Text(original.text, style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = Gutter))
            }
        }
        Heading(stringResource(Res.string.script_expressions))
        val expressions = state.script?.expressions.orEmpty()
        if (expressions.isEmpty()) {
            Text(stringResource(Res.string.script_expressions_empty), style = MontageTheme.typography.body2.regular, color = MontageTheme.colors.foregroundNeutralSecondary, modifier = Modifier.padding(horizontal = Gutter))
        }
        expressions.forEach { UsedExpression(it) }
    }
}

/** 다듬기 버튼 글이 길어 나란히 두면 잘려서 위아래로 쌓는다. */
@Composable
private fun ScriptActions(polishing: Boolean, onPolish: () -> Unit, onPractice: () -> Unit) {
    Column(Modifier.padding(horizontal = Gutter, vertical = MontageSpacing.spacing12), verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        MontageButton(
            text = stringResource(Res.string.script_polish),
            onClick = onPolish,
            modifier = Modifier.fillMaxWidth(),
            variant = MontageButtonVariant.Outlined,
            type = MontageButtonType.Assistive,
            loading = polishing,
        )
        MontageButton(text = stringResource(Res.string.script_practice), onClick = onPractice, modifier = Modifier.fillMaxWidth(), enabled = !polishing)
    }
}

@Composable
private fun UsedExpression(expression: Expression) {
    Column(Modifier.padding(horizontal = Gutter).semantics(mergeDescendants = true) {}) {
        Text(expression.english, style = MontageTheme.typography.body1.bold, color = MontageTheme.colors.foregroundNeutralPrimary)
        Text(stringResource(Res.string.live_expression_said, expression.userSaid), style = MontageTheme.typography.label1.regular, color = MontageTheme.colors.foregroundNeutralSecondary)
    }
}

@Composable
private fun ScriptNotice(text: String) {
    Text(text, style = MontageTheme.typography.body2.medium, color = MontageTheme.colors.foregroundNeutralPrimary, modifier = Modifier.padding(horizontal = Gutter))
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MontageTheme.typography.label1.bold,
        color = MontageTheme.colors.foregroundNeutralSecondary,
        modifier = Modifier.padding(horizontal = Gutter).padding(top = MontageSpacing.spacing8).semantics { heading() },
    )
}

private operator fun ScriptParts.get(part: ScriptPart): String = when (part) {
    ScriptPart.Intro -> intro
    ScriptPart.Body -> body
    ScriptPart.Closing -> closing
}

/** 연습 화면에 넘기고 원본 보기에 보이는 글. 세 칸을 문단으로 잇는다. */
private val ScriptParts.text: String get() = listOf(intro, body, closing).filter { it.isNotBlank() }.joinToString("\n\n")

private val ScriptPart.label: StringResource
    get() = when (this) {
        ScriptPart.Intro -> Res.string.script_intro
        ScriptPart.Body -> Res.string.script_body
        ScriptPart.Closing -> Res.string.script_closing
    }

private val AnalysisError.message: StringResource
    get() = when (this) {
        AnalysisError.InvalidKey -> Res.string.script_error_key
        AnalysisError.Offline -> Res.string.script_error_offline
        AnalysisError.Retryable -> Res.string.script_error_busy
        AnalysisError.ModelUnavailable -> Res.string.script_error_model
        else -> Res.string.script_error_rejected
    }

@Composable
private fun ScriptLength.timeText(): String = if (shortest == longest) stringResource(Res.string.script_time_about, spoken(shortest)) else "${spoken(shortest)}~${spoken(longest)}"

@Composable
private fun spoken(duration: Duration): String {
    val minutes = duration.inWholeMinutes.toInt()
    val seconds = (duration - minutes.minutes).inWholeSeconds.toInt()
    return if (seconds == 0) stringResource(Res.string.script_minutes, minutes) else stringResource(Res.string.script_minutes_seconds, minutes, seconds)
}

private val Gutter = MontageSpacing.spacing20

/** 미리보기와 스크린샷 테스트가 함께 쓰는 고정 상태. */
internal object CoachScriptSamples {
    private const val QUESTION = "Tell me about a memorable trip you took recently. Where did you go, and what made it special?"
    private val length = ScriptLength(90.seconds, 2.minutes, 12..16)
    private val script = CoachScript(
        sessionId = "c1",
        original = ScriptParts(
            intro = "I want to talk about a trip to Busan that I took with my sister last summer.",
            body = "We stayed near Haeundae beach for three days. Every morning we walked along the sand and ate fresh seafood at the market. " +
                "On the last night we watched fireworks over the sea, and it was an absolute blast.",
            closing = "That trip was special because I spent real time with my sister. I want to go back next year.",
        ),
        expressions = listOf(Expression("진짜 좋았어", "It was an absolute blast", "IH"), Expression("시간을 제대로 보냈어", "spent real time with", "IM3")),
        edited = null,
        editedAt = null,
        promptVersion = "script-v1",
        model = "gemini-3.8-flash",
    )

    val written = CoachScriptUiState(question = QUESTION, script = script, writing = false, targetGrade = "IH", length = length)
    val edited = written.copy(script = script.copy(edited = script.original.copy(closing = "I really want to go back next summer with my whole family."), editedAt = Instant.fromEpochSeconds(0)))
    val writing = CoachScriptUiState(question = QUESTION)
    val failed = CoachScriptUiState(question = QUESTION, writing = false, error = AnalysisError.Offline)
}

@Preview
@Composable
private fun CoachScriptScreenPreview() {
    MontageTheme { CoachScriptScreen(CoachScriptSamples.written, onIntent = {}, onPractice = {}) }
}
