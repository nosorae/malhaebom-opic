package io.github.nosorae.aiopic.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Montage 아래 시트. 위 두 모서리만 24dp 로 둥글고 가운데에 손잡이를 둔다. 배경은 떠 있는 표면, 뒤 화면은 Montage 딤 색으로 가린다.
 * 반만 펼친 단계를 두지 않는다. 큰 글자에서 아래 버튼이 반쯤 가려진 채로 뜨지 않게 한다.
 * 끌어 내리기, 바깥 누르기, 뒤로가기로 닫히는 동작은 Material 3 시트를 그대로 쓴다. [content] 의 `close` 는 시트를 내린 뒤 받은 일을 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MontageBottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit) {
    val colors = MontageTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = state,
        shape = RoundedCornerShape(topStart = MontageRadius.radius24, topEnd = MontageRadius.radius24),
        containerColor = colors.surfaceElevatedPrimary,
        contentColor = colors.foregroundNeutralPrimary,
        scrimColor = colors.effectDimmerPrimary,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = MontageSpacing.spacing12)
                    .size(width = MontageSpacing.spacing40, height = MontageSpacing.spacing4)
                    .background(colors.lineNeutralPrimary, RoundedCornerShape(MontageRadius.radius4)),
            )
        },
    ) {
        content { then -> scope.launch { state.hide() }.invokeOnCompletion { then() } }
    }
}
