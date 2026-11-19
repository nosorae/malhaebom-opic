package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val CardKind.label: StringResource
    get() = when (this) {
        CardKind.Correction -> Res.string.kind_correction
        CardKind.UnknownExpression -> Res.string.kind_unknown
        CardKind.Formulaic -> Res.string.kind_formulaic
        CardKind.MoreNatural -> Res.string.kind_more_natural
    }

internal val ErrorType.label: StringResource
    get() = when (this) {
        ErrorType.Tense -> Res.string.error_tense
        ErrorType.Article -> Res.string.error_article
        ErrorType.WordChoice -> Res.string.error_word_choice
        ErrorType.Connector -> Res.string.error_connector
        ErrorType.SentenceStructure -> Res.string.error_sentence_structure
    }

/** 목록 줄의 꼬리표(F18.1, F17.6). 고칠 표현은 틀린 유형, 더 자연스럽게는 "틀린 건 아니에요", 나머지는 종류 이름이다. 연습 정리 화면도 같이 쓴다. */
@Composable
fun cardTag(card: ExpressionCard): String = when (card.kind) {
    CardKind.Correction -> stringResource(card.errorType?.label ?: CardKind.Correction.label)
    CardKind.MoreNatural -> stringResource(Res.string.expressions_not_wrong)
    else -> stringResource(card.kind.label)
}

/**
 * 내가 한 말이나 더 나은 표현에서 다른 부분에 색을 칠한다(F18.3, F18.5). [better] 면 들어간 낱말을 강조색 굵은 글자로,
 * 아니면 빠진 낱말을 빨간 밑줄로 보인다. 색 하나로만 알리지 않게 굵기와 밑줄을 함께 쓴다.
 */
@Composable
internal fun DiffText(card: ExpressionCard, better: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = MontageTheme.colors
    val ops = remember(card.said, card.better) { diffWords(card.said, card.better) }
    val text = buildAnnotatedString {
        ops.filter { if (better) it !is WordOp.Removed else it !is WordOp.Added }.forEachIndexed { index, op ->
            if (index > 0) append(' ')
            when {
                op is WordOp.Same -> append(op.word)
                better -> withStyle(SpanStyle(color = colors.foregroundBrandPrimary, fontWeight = FontWeight.Bold)) { append(op.word) }
                else -> withStyle(SpanStyle(color = colors.foregroundNegativePrimary, textDecoration = TextDecoration.Underline)) { append(op.word) }
            }
        }
    }
    Text(text, style = style, color = colors.foregroundNeutralPrimary, modifier = modifier)
}
