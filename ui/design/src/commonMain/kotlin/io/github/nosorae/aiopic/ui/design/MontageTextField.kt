package io.github.nosorae.aiopic.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Montage 한 줄 입력칸(Large, 높이 48dp). [title] 은 칸 위 이름, [description] 은 칸 아래 도움말이나 오류 문구다.
 * 초점이 있으면 테두리가 강조색이 되고, [error] 면 빨강이 된다.
 * [trailing] 은 칸 오른쪽 끝의 48dp 아이콘 버튼 자리다(지우기 같은 것). 칸 높이는 그대로 48dp 다.
 * [fontFamily] 는 입력 글자 서체다. API 키처럼 l, 1, I 를 가려 읽어야 하는 값은 고정폭을 넣는다.
 * 도움말과 오류 문구는 줄 수를 막지 않는다. 글자를 키운 화면에서 잘리지 않게 하려는 것이다.
 * [singleLine] 이 false 면 글이 길어지는 대로 칸이 아래로 늘어난다(스크립트 문단).
 */
@Composable
fun MontageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    placeholder: String = "",
    description: String? = null,
    error: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    fontFamily: FontFamily? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(MontageRadius.radius14)
    val border = when {
        !enabled -> colors.lineNeutralTertiary
        error && focused -> colors.foregroundNegativePrimary.copy(alpha = 0.52f)
        error -> colors.foregroundNegativePrimary.copy(alpha = 0.43f)
        focused -> colors.surfaceBrandPrimary.copy(alpha = 0.43f)
        else -> colors.lineNeutralSecondary
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MontageSpacing.spacing8)) {
        if (title != null) {
            Text(title, style = typography.label1.bold, color = colors.foregroundNeutralSecondary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = typography.body2.regular.copy(
                color = if (enabled) colors.foregroundNeutralPrimary else colors.foregroundNeutralTertiary,
                fontFamily = fontFamily ?: typography.body2.regular.fontFamily,
            ),
            cursorBrush = SolidColor(colors.foregroundBrandPrimary),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .background(if (enabled) colors.backgroundNeutralPrimary else colors.surfaceNeutralTertiary, shape)
                        .border(1.dp, border, shape)
                        .padding(start = 12.dp, end = if (trailing == null) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = typography.body2.regular,
                                color = if (enabled) colors.foregroundNeutralTertiary else colors.foregroundDisablePrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
        if (description != null) {
            Text(
                text = description,
                style = typography.caption1.regular,
                color = if (enabled && error) colors.foregroundNegativePrimary else colors.foregroundNeutralTertiary,
            )
        }
    }
}
