package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.ui.test.junit4.createComposeRule
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

/** 코치 대화 화면(F11, F12, F15.1). 목소리 띠와 기다림 물결이 계속 움직여 시계를 멈춘 첫 장면을 찍는다. 입력은 [LiveCoachSamples] 다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class LiveCoachScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun exploring() = still(LiveCoachSamples.exploring)

    @Test fun exploring_dark() = still(LiveCoachSamples.exploring, dark = true)

    /** 요약 줄을 눌러 소재 메모와 표현 카드를 펼친다. 펼친 목록과 받아쓰기가 함께 보이게 긴 화면으로 찍는다. */
    @Config(qualifiers = "w360dp-h1400dp-xhdpi")
    @Test
    fun notesOpen() = still(LiveCoachSamples.exploring, openNotes = true)

    @Config(qualifiers = "w360dp-h1400dp-xhdpi")
    @Test
    fun notesOpen_dark() = still(LiveCoachSamples.exploring, dark = true, openNotes = true)

    @Config(fontScale = 2f)
    @Test
    fun exploring_fontScale200() = still(LiveCoachSamples.exploring)

    @Test fun dropped() = still(LiveCoachSamples.dropped)

    @Test fun dropped_dark() = still(LiveCoachSamples.dropped, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun dropped_fontScale200() = still(LiveCoachSamples.dropped)

    /** 키가 틀려 끊기면 다시 연결 대신 키 설정 열기를 보인다. */
    @Test fun invalidKey() = still(LiveCoachSamples.invalidKey)

    @Test fun invalidKey_dark() = still(LiveCoachSamples.invalidKey, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun invalidKey_fontScale200() = still(LiveCoachSamples.invalidKey)

    @Test fun timeWarning() = still(LiveCoachSamples.timeWarning)

    @Test fun timeWarning_dark() = still(LiveCoachSamples.timeWarning, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun timeWarning_fontScale200() = still(LiveCoachSamples.timeWarning)

    @Test fun consent() = still(LiveCoachSamples.consent)

    @Config(fontScale = 2f)
    @Test
    fun consent_fontScale200() = still(LiveCoachSamples.consent)

    @Test fun finished() = still(LiveCoachSamples.finished, millis = 1_200)

    /** 물결은 점이 모인 장면이 보이게 [millis] 만큼 넘긴다. 0 이면 첫 장면이다. */
    private fun still(state: LiveCoachUiState, dark: Boolean = false, millis: Long = 0, openNotes: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent { MontageTheme(darkTheme = dark) { LiveCoachScreen(state, onIntent = {}, onExit = {}) } }
        rule.mainClock.advanceTimeByFrame()
        if (millis > 0) rule.mainClock.advanceTimeBy(millis)
        if (openNotes) {
            rule.onNodeWithText("소재 메모와 표현 카드").performClick()
            rule.mainClock.advanceTimeBy(500)
        }
        rule.onRoot().captureRoboImage()
    }
}
