package io.github.nosorae.aiopic.ui.featureexpressions

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 표현 탭, 표현 상세, 복습 화면. 화면마다 라이트, 다크, 글자 200% 를 찍고 상태마다 한 장을 더 찍는다. 입력은 각 화면의 Samples 다.
 * 목록 줄이 나타나는 움직임은 동작 줄이기로 끝 장면을 찍는다.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ExpressionsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tab() = shot { tab(ExpressionSamples.tab) }

    @Test fun tab_dark() = shot(dark = true) { tab(ExpressionSamples.tab) }

    @Config(fontScale = 2f)
    @Test
    fun tab_fontScale200() = shot { tab(ExpressionSamples.tab) }

    @Test fun tabArchived() = shot { tab(ExpressionSamples.tab.copy(filter = ExpressionFilter.Archived)) }

    @Test fun tabEmpty() = shot { tab(ExpressionSamples.emptyTab) }

    @Test fun detail() = shot { detail(ExpressionDetailSamples.detail) }

    @Test fun detail_dark() = shot(dark = true) { detail(ExpressionDetailSamples.detail) }

    @Config(fontScale = 2f)
    @Test
    fun detail_fontScale200() = shot { detail(ExpressionDetailSamples.detail) }

    @Test fun detailKnown() = shot { detail(ExpressionDetailSamples.known) }

    @Test fun detailArchived() = shot { detail(ExpressionDetailSamples.archived) }

    @Test fun detailEditing() = shot { detail(ExpressionDetailSamples.editing) }

    /** 삭제 확인은 따로 뜨는 창이라 화면 전체를 찍는다. */
    @Test
    fun detailDelete() {
        rule.setContent { MontageTheme(reduceMotion = true) { detail(ExpressionDetailSamples.detail.copy(confirmingDelete = true)) } }
        rule.onNodeWithText("이 표현을 지울까요?").assertExists()
        captureScreenRoboImage()
    }

    @Test fun reviewFirstSeen() = shot { review(ReviewSamples.firstSeen) }

    @Test fun reviewFirstSeen_dark() = shot(dark = true) { review(ReviewSamples.firstSeen) }

    @Config(fontScale = 2f)
    @Test
    fun reviewFirstSeen_fontScale200() = shot { review(ReviewSamples.firstSeen) }

    @Test fun reviewCorrectionFront() = shot { review(ReviewSamples.correctionFront) }

    @Test fun reviewUnknownFront() = shot { review(ReviewSamples.unknownFront) }

    @Test fun reviewNaturalFrontSilent() = shot { review(ReviewSamples.naturalFront) }

    @Test fun reviewRevealed() = shot { review(ReviewSamples.revealed) }

    @Test fun reviewRevealed_dark() = shot(dark = true) { review(ReviewSamples.revealed) }

    @Config(fontScale = 2f)
    @Test
    fun reviewRevealed_fontScale200() = shot { review(ReviewSamples.revealed) }

    @Test fun reviewAllRatings() = shot { review(ReviewSamples.allRatings) }

    @Test fun reviewDone() = shot { review(ReviewSamples.done) }

    @Test fun reviewDone_dark() = shot(dark = true) { review(ReviewSamples.done) }

    @Config(fontScale = 2f)
    @Test
    fun reviewDone_fontScale200() = shot { review(ReviewSamples.done) }

    @Test fun reviewEmpty() = shot { review(ReviewSamples.empty) }

    @Composable
    private fun tab(state: ExpressionsUiState) = ExpressionsScreen(state, {}, {}, {}, {})

    @Composable
    private fun detail(state: ExpressionDetailUiState) = ExpressionDetailScreen(state, {})

    @Composable
    private fun review(state: ReviewUiState) = ReviewScreen(state, {}, {})

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) = captureRoboImage { MontageTheme(darkTheme = dark, reduceMotion = true) { content() } }
}
