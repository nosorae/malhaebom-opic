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

    private fun open(dark: Boolean = false) {
        rule.setContent {
            MontageTheme(darkTheme = dark, reduceMotion = true) { CoachStartSheet(CoachStartSamples.default, onIntent = {}, onDismiss = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("시작").assertExists()
        captureScreenRoboImage()
    }
}
