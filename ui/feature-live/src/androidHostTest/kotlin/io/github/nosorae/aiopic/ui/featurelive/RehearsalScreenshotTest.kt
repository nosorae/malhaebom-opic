package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 리허설 단계와 피드백 단계의 "한 번 더 리허설"(F13). 목소리 띠가 계속 움직여 시계를 멈춘 첫 장면을 찍는다. 입력은 [RehearsalSamples] 다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class RehearsalScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rehearsalReady() = still(RehearsalSamples.ready)

    @Test fun rehearsalRecording() = still(RehearsalSamples.recording)

    @Test fun rehearsalRecording_dark() = still(RehearsalSamples.recording, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun rehearsalRecording_fontScale200() = still(RehearsalSamples.recording)

    @Test fun rehearsalTranscriptFailed() = still(RehearsalSamples.transcriptFailed)

    @Test fun feedbackRehearseAgain() = still(RehearsalSamples.feedbackOnce)

    @Test
    fun `F13_3 리허설을 두 번 했으면 한 번 더 리허설 버튼이 없다`() {
        show(RehearsalSamples.feedbackTwice)

        rule.onAllNodesWithText("한 번 더 리허설").assertCountEquals(0)
    }

    @Test
    fun `F13_3 리허설을 한 번 했으면 한 번 더 리허설 버튼이 있다`() {
        show(RehearsalSamples.feedbackOnce)

        rule.onAllNodesWithText("한 번 더 리허설").assertCountEquals(1)
    }

    private fun show(state: LiveCoachUiState, dark: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent { MontageTheme(darkTheme = dark) { LiveCoachScreen(state, onIntent = {}, onExit = {}) } }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun still(state: LiveCoachUiState, dark: Boolean = false) {
        show(state, dark)
        rule.onRoot().captureRoboImage()
    }
}
