package io.github.nosorae.aiopic.ui.featurespeaking

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
 * 연습 시작 시트, 연습 중 화면, 연습 정리 화면. 화면마다 라이트, 다크, 글자 200% 를 찍고 상태마다 한 장을 더 찍는다.
 * 목소리 띠와 기다림 표시는 계속 돌아서 동작 줄이기로 멈춘 장면을 찍는다. 시트는 따로 뜨는 창이라 화면 전체를 찍는다.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SpeakingScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun start() = sheet(SpeakingStartSamples.earphones)

    @Test fun start_dark() = sheet(SpeakingStartSamples.earphones, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun start_fontScale200() = sheet(SpeakingStartSamples.earphones)

    /** 스피커면 휴대폰 두는 곳을 안내하고, 동의 전이면 동의 문구와 "동의하고 시작" 을 보인다(F16.2, F16.3). */
    @Test fun startSpeakerConsent() = sheet(SpeakingStartSamples.speaker, button = "동의하고 시작")

    /** 키가 없으면 시작을 흐리게 두고 키 넣기를 보인다(F16.3). */
    @Test fun startNoKey() = sheet(SpeakingStartSamples.noKey)

    @Test fun running() = shot { running(SpeakingSamples.running) }

    @Test fun running_dark() = shot(dark = true) { running(SpeakingSamples.running) }

    @Config(fontScale = 2f)
    @Test
    fun running_fontScale200() = shot { running(SpeakingSamples.running) }

    @Test fun runningCoach() = shot { running(SpeakingSamples.coach) }

    @Test fun runningPaused() = shot { running(SpeakingSamples.paused) }

    @Test fun runningDropped() = shot { running(SpeakingSamples.dropped) }

    @Test fun summary() = shot { summary(SpeakingSummarySamples.done) }

    @Test fun summary_dark() = shot(dark = true) { summary(SpeakingSummarySamples.done) }

    @Config(fontScale = 2f)
    @Test
    fun summary_fontScale200() = shot { summary(SpeakingSummarySamples.done) }

    @Test fun summaryWriting() = shot { summary(SpeakingSummarySamples.writing) }

    @Test fun summaryFailed() = shot { summary(SpeakingSummarySamples.failed) }

    @Test fun summaryEmpty() = shot { summary(SpeakingSummarySamples.empty) }

    @Test fun summaryRemoved() = shot { summary(SpeakingSummarySamples.removed) }

    @Composable
    private fun running(state: SpeakingUiState) = SpeakingScreen(state, {}, {})

    @Composable
    private fun summary(state: SpeakingSummaryUiState) = SpeakingSummaryScreen(state, {}, {}, {})

    private fun shot(dark: Boolean = false, content: @Composable () -> Unit) = captureRoboImage { MontageTheme(darkTheme = dark, reduceMotion = true) { content() } }

    private fun sheet(state: SpeakingStartUiState, dark: Boolean = false, button: String = "시작") {
        rule.setContent { MontageTheme(darkTheme = dark, reduceMotion = true) { SpeakingStartSheet(state, {}, {}, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(button).assertExists()
        captureScreenRoboImage()
    }
}
