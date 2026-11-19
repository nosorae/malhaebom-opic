package io.github.nosorae.aiopic.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

enum class MontageButtonVariant { Solid, Outlined, Text }

/** Assistive 는 회색 보조 버튼, Negative 는 지우기처럼 되돌릴 수 없는 동작이다(Solid 만). */
enum class MontageButtonType { Primary, Assistive, Negative }

/** Text 변형은 배경이 없어 Montage 가 높이를 따로 둔다([textMinHeight]). */
enum class MontageButtonSize(
    internal val minHeight: Dp,
    internal val textMinHeight: Dp,
    internal val horizontalPadding: Dp,
    internal val iconSize: Dp,
) {
    Large(48.dp, 40.dp, 20.dp, 20.dp),
    Medium(40.dp, 32.dp, 16.dp, 18.dp),
    Small(32.dp, 28.dp, 12.dp, 16.dp),
}

/**
 * Montage 버튼. 모양은 Montage 값을 따르고, 눈에 보이는 높이가 48dp 보다 작아도 눌리는 영역은 48dp 로 넓힌다.
 * [loading] 이면 글자 대신 진행 표시를 보이고 누를 수 없다. 글자는 투명하게 남겨 버튼 크기가 바뀌지 않게 한다.
 * [fill] 을 주면 배경 위에 왼쪽부터 그 비율(0~1)만큼 옅은 강조색을 채운다. 버튼이 켜지기까지 남은 시간을 보일 때 쓰고, 그리는 단계에서만 읽는다.
 * 누르는 동안 살짝 줄어 눌린 것이 손가락 밑에서도 보인다. 꺼져 있던 버튼이 켜지면 한 번 살짝 튀어 이제 누를 수 있다는 것이 보인다.
 * 처음 그릴 때 이미 켜져 있으면 튀지 않는다. 동작 줄이기면 둘 다 없다.
 * [onLongClick] 을 주면 길게 누를 때 부른다. 화면 읽기 도구에는 길게 누르기 동작으로 읽힌다.
 */
@Composable
fun MontageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MontageButtonVariant = MontageButtonVariant.Solid,
    type: MontageButtonType = MontageButtonType.Primary,
    size: MontageButtonSize = MontageButtonSize.Large,
    enabled: Boolean = true,
    leadingIcon: DrawableResource? = null,
    loading: Boolean = false,
    fill: (() -> Float)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MontageTheme.colors
    val typography = MontageTheme.typography
    val content = buttonContentColor(variant, type, enabled)
    val background = when {
        variant != MontageButtonVariant.Solid -> Color.Transparent
        !enabled -> colors.surfaceDisablePrimary
        type == MontageButtonType.Assistive -> colors.surfaceNeutralSecondary
        type == MontageButtonType.Negative -> colors.foregroundNegativePrimary
        else -> colors.surfaceBrandPrimary
    }
    val isText = variant == MontageButtonVariant.Text
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressScale(interaction)
    val pop = rememberEnablePop(enabled)
    val shape = RoundedCornerShape(
        when (size) {
            MontageButtonSize.Large -> if (isText) MontageRadius.radius10 else MontageRadius.radius14
            MontageButtonSize.Medium -> if (isText) MontageRadius.radius8 else MontageRadius.radius12
            MontageButtonSize.Small -> if (isText) MontageRadius.radius8 else MontageRadius.radius10
        },
    )
    val textStyle = when {
        isText && size == MontageButtonSize.Small -> typography.label1.bold
        isText -> typography.body1.bold
        size == MontageButtonSize.Large -> typography.body2.bold
        size == MontageButtonSize.Medium -> typography.label1.bold
        else -> typography.caption1.bold
    }
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = press.value * pop.value
                scaleY = scaleX
            }
            .clip(shape)
            .background(background)
            .then(
                if (fill != null) {
                    val color = colors.surfaceBrandPrimaryOpacity12
                    Modifier.drawBehind { drawRect(color, size = this.size.copy(width = this.size.width * fill().coerceIn(0f, 1f))) }
                } else {
                    Modifier
                },
            )
            .then(
                if (variant == MontageButtonVariant.Outlined) {
                    Modifier.border(BorderStroke(1.dp, colors.lineNeutralSecondary), shape)
                } else {
                    Modifier
                },
            )
            // 화면 읽기 도구가 처리 중인 버튼을 "처리 중, 사용할 수 없음" 으로 읽게 버튼에 설명을 단다.
            // ponytail: 앱이 한국어 하나라 문구를 여기 둔다. 언어를 더하면 리소스로 옮긴다.
            .then(if (loading) Modifier.semantics { contentDescription = "처리 중" } else Modifier)
            .combinedClickable(
                enabled = enabled && !loading,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(color = content),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .heightIn(min = if (isText) size.textMinHeight else size.minHeight)
            .padding(horizontal = if (isText) 7.dp else size.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = if (loading) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier,
                horizontalArrangement = Arrangement.spacedBy(MontageSpacing.spacing4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(painterResource(leadingIcon), contentDescription = null, tint = content, modifier = Modifier.size(size.iconSize))
                }
                Text(text = text, style = textStyle, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loading) {
                CircularProgressIndicator(
                    color = content,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(size.iconSize).clearAndSetSemantics {},
                )
            }
        }
    }
}

/** 누르는 동안 0.96 으로 줄었다가 떼면 돌아오는 크기. 그리는 단계에서 읽는다. 버튼과 칩이 같이 쓴다. */
@Composable
internal fun rememberPressScale(interaction: MutableInteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(if (pressed && !MontageMotion.reduceMotion) PRESSED_SCALE else 1f, MontageMotion.spec(MontageMotion.SHORT_MILLIS), label = "press")
}

/** 꺼졌다 켜지는 순간 작게 줄었다가 스프링으로 한 번 지나쳐 제 크기로 돌아온다. */
@Composable
private fun rememberEnablePop(enabled: Boolean): State<Float> {
    val reduceMotion = MontageMotion.reduceMotion
    val spec = MontageMotion.settle<Float>()
    val pop = remember { Animatable(1f) }
    val wasEnabled = remember { booleanArrayOf(enabled) }
    LaunchedEffect(enabled) {
        if (enabled && !wasEnabled[0] && !reduceMotion) {
            pop.snapTo(POP_FROM)
            pop.animateTo(1f, spec)
        }
        wasEnabled[0] = enabled
    }
    return pop.asState()
}

private const val PRESSED_SCALE = 0.96f
private const val POP_FROM = 0.92f

@Composable
private fun buttonContentColor(variant: MontageButtonVariant, type: MontageButtonType, enabled: Boolean): Color {
    val colors = MontageTheme.colors
    return when {
        !enabled -> colors.foregroundDisablePrimary

        variant == MontageButtonVariant.Solid -> when (type) {
            MontageButtonType.Primary -> colors.onBrand
            MontageButtonType.Assistive -> colors.foregroundNeutralSecondary
            MontageButtonType.Negative -> colors.onBrand
        }

        variant == MontageButtonVariant.Outlined && type == MontageButtonType.Assistive -> colors.foregroundNeutralPrimary

        variant == MontageButtonVariant.Text && type == MontageButtonType.Assistive -> colors.foregroundNeutralTertiary

        type == MontageButtonType.Negative -> colors.foregroundNegativePrimary

        else -> colors.foregroundBrandPrimary
    }
}
