package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 코치 시작 시트(F10.2~F10.5). 시트는 따로 뜨는 창이라 화면 전체를 찍는다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CoachStartSheetScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = open()

    @Test fun dark() = open(dark = true)

    @Config(fontScale = 2f)
    @Test
    fun fontScale200() = open()

    /** 끝내지 못한 대화가 있으면 이어서 하기와 새 질문으로 시작을 고른다(F11.8). */
    @Test fun unfinished() = open(CoachStartSamples.unfinished, button = "이어서 하기")

    @Test fun unfinished_dark() = open(CoachStartSamples.unfinished, dark = true, button = "이어서 하기")

    @Config(fontScale = 2f)
    @Test
    fun unfinished_fontScale200() = open(CoachStartSamples.unfinished, button = "이어서 하기")

    private fun open(state: CoachStartUiState = CoachStartSamples.default, dark: Boolean = false, button: String = "시작") {
        rule.setContent {
            MontageTheme(darkTheme = dark, reduceMotion = true) { CoachStartSheet(state, onIntent = {}, onResume = {}, onDismiss = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithText(button).assertExists()
        captureScreenRoboImage()
    }
}
