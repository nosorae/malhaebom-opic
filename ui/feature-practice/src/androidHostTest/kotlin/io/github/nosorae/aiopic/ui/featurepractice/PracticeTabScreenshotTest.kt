package io.github.nosorae.aiopic.ui.featurepractice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 연습 탭. 약한 유형이 있을 때와 없을 때, 다크, 글자 200%. 입력은 [PracticeTabSamples] 다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class PracticeTabScreenshotTest {
    @Test fun weak() = shot(PracticeTabSamples.weak)

    @Test fun weak_dark() = shot(PracticeTabSamples.weak, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun weak_fontScale200() = shot(PracticeTabSamples.weak)

    @Test fun anyType() = shot(PracticeTabSamples.anyType)

    private fun shot(state: PracticeTabUiState, dark: Boolean = false) = captureRoboImage {
        MontageTheme(darkTheme = dark) { PracticeTabScreen(state = state, onStart = {}, onOpenCoach = {}) }
    }
}
