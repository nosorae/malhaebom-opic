package io.github.nosorae.aiopic.ui.featurepractice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 질문을 고르고 읽는 화면과 녹음 중 화면. 물결과 목소리 띠가 계속 움직여 시계를 멈춘 첫 장면을 찍는다. 라이트, 다크, 글자 200% 다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class PracticeRecordingScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loading() = still(PracticeSamples.loading, millis = 1_200)

    @Test fun speaking() = still(PracticeSamples.speaking, millis = 1_200)

    @Test fun replayable() = still(PracticeSamples.replayable)

    @Test fun nextable() = still(PracticeSamples.nextable)

    @Test fun saving() = still(PracticeSamples.saving)

    @Test fun replayable_dark() = still(PracticeSamples.replayable, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun replayable_fontScale200() = still(PracticeSamples.replayable)

    /** F14.5 스크립트로 연습하면 녹음 중에 스크립트가 보인다. */
    @Test fun scriptRecording() = still(PracticeSamples.scriptRecording)

    @Test fun scriptRecording_dark() = still(PracticeSamples.scriptRecording, dark = true)

    @Test
    fun `F14_5 스크립트 제목 줄을 누르면 스크립트를 접는다`() {
        still(PracticeSamples.scriptRecording, capture = false)

        rule.onNodeWithTag("practice-script-toggle").performClick()
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithText("We stayed", substring = true).assertDoesNotExist()
    }

    /** F14.5 AL 길이 스크립트도 녹음 상태와 목소리 띠를 화면 밖으로 밀지 않는다. 스크립트는 칸 안에서 스크롤한다. */
    @Config(fontScale = 2f)
    @Test
    fun `F14_5 긴 스크립트를 펴도 녹음 상태가 화면 안에 보인다`() {
        still(PracticeSamples.longScriptRecording, capture = false)

        rule.onNodeWithText("녹음 중", substring = true).assertIsDisplayed()
    }

    @Test fun longScriptRecording() = still(PracticeSamples.longScriptRecording)

    @Test fun longScriptRecording_dark() = still(PracticeSamples.longScriptRecording, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun longScriptRecording_fontScale200() = still(PracticeSamples.longScriptRecording)

    /** 물결은 점이 모인 장면이 보이게 [millis] 만큼 넘긴다. 0 이면 첫 장면이다. */
    private fun still(state: PracticeUiState, dark: Boolean = false, millis: Long = 0, capture: Boolean = true) {
        rule.mainClock.autoAdvance = false
        rule.setContent { MontageTheme(darkTheme = dark) { PracticeScreen(state = state, onIntent = {}) } }
        rule.mainClock.advanceTimeByFrame()
        if (millis > 0) rule.mainClock.advanceTimeBy(millis)
        if (capture) rule.onRoot().captureRoboImage()
    }
}
