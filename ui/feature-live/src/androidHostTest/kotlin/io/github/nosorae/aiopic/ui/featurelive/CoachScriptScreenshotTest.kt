package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 스크립트 화면(F14). 라이트, 다크, 글자 200% 와 만드는 중, 실패, 고친 판의 원본 보기를 찍는다. 입력은 [CoachScriptSamples] 다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1000dp-xhdpi")
class CoachScriptScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun written() = still(CoachScriptSamples.written)

    @Test fun written_dark() = still(CoachScriptSamples.written, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun written_fontScale200() = still(CoachScriptSamples.written)

    @Test fun writing() = still(CoachScriptSamples.writing, millis = 1_200)

    @Test fun failed() = still(CoachScriptSamples.failed)

    @Test
    fun editedOriginal() {
        show(CoachScriptSamples.edited)
        rule.onNodeWithText("원본 보기").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun `F14_5 이 스크립트로 연습은 세 칸을 문단으로 이은 글을 넘긴다`() {
        var practiced: String? = null
        show(CoachScriptSamples.edited, onPractice = { practiced = it })

        rule.onNodeWithText("이 스크립트로 연습").performClick()

        val parts = CoachScriptSamples.edited.parts!!
        assertEquals("${parts.intro}\n\n${parts.body}\n\n${parts.closing}", practiced)
    }

    private fun show(state: CoachScriptUiState, dark: Boolean = false, onPractice: (String) -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent { MontageTheme(darkTheme = dark) { CoachScriptScreen(state, onIntent = {}, onPractice = onPractice) } }
        rule.mainClock.advanceTimeByFrame()
    }

    /** 물결은 점이 모인 장면이 보이게 [millis] 만큼 넘긴다. */
    private fun still(state: CoachScriptUiState, dark: Boolean = false, millis: Long = 0) {
        show(state, dark)
        if (millis > 0) rule.mainClock.advanceTimeBy(millis)
        rule.onRoot().captureRoboImage()
    }
}
