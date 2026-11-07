package io.github.nosorae.aiopic.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class MontageChipVariant { Solid, Outlined }

/**
 * Montage 칩(Medium 크기, 높이 36dp). [active] 는 선택된 칩이다. 서베이 주제 고르기, 필터에 쓴다.
 * 고르면 배경이 짧게 강조색으로 차올라 무엇을 골랐는지 바로 보인다. 누르는 동안 버튼처럼 살짝 줄어든다.
 */
@Composable
fun MontageChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    variant: MontageChipVariant = MontageChipVariant.Solid,
    enabled: Boolean = true,
) {
    val colors = MontageTheme.colors
    val shape = RoundedCornerShape(MontageRadius.radius10)
    val solid = variant == MontageChipVariant.Solid
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressScale(interaction)
    val background by animateColorAsState(
        when {
            !enabled -> if (solid) colors.surfaceDisablePrimary else Color.Transparent
            active -> colors.surfaceBrandPrimaryOpacity5
            solid -> colors.surfaceNeutralTertiary
            else -> Color.Transparent
        },
        MontageMotion.spec(MontageMotion.SHORT_MILLIS),
    )
    val border = when {
        solid -> Color.Transparent
        enabled && active -> colors.surfaceBrandPrimaryOpacity28
        else -> colors.lineNeutralSecondary
    }
    val content = when {
        !enabled -> colors.foregroundDisablePrimary
        active -> colors.foregroundBrandPrimary
        else -> colors.foregroundNeutralPrimary
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            // 스크린리더가 선택됨과 선택 안 됨을 읽는다.
            .toggleable(
                value = active,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = ripple(color = content),
                onValueChange = { onClick() },
            )
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MontageTheme.typography.label2.medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
