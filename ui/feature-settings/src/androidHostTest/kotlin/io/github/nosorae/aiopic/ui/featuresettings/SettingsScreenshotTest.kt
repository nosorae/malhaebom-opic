package io.github.nosorae.aiopic.ui.featuresettings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 설정 탭은 모든 묶음이 한 화면에 다 보이게 높이를 넉넉히 잡는다. 지우기, 철회 확인 대화상자는 창이 따로라 찍지 않는다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2600dp-xhdpi")
class SettingsScreenshotTest {
    @Test fun light() = shot(SettingsSamples.default)

    @Test fun dark() = shot(SettingsSamples.dark, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun fontScale200() = shot(SettingsSamples.default)

    @Test fun previewSelected() = shot(SettingsSamples.previewSelected)

    @Test fun previewSelectedDark() = shot(SettingsSamples.previewSelected, dark = true)

    @Test fun fellBack() = shot(SettingsSamples.fellBack)

    /** 동의 철회 뒤, 답변 타이머 켬, 틀린 문항 파일을 고른 뒤(F1.3, F2.7, F6.3). */
    @Test fun practiceSet() = shot(SettingsSamples.practiceSet)

    @Test fun practiceSetDark() = shot(SettingsSamples.practiceSet, dark = true)

    @Test fun imported() = shot(SettingsSamples.imported)

    /** #161: 기기 음성을 골랐는데 고품질 기기 음성이 없으면 받는 곳을 안내한다. */
    @Test fun deviceVoice() = shot(SettingsSamples.deviceVoice)

    /** F19.1, F19.2: 말하기 연습과 복습 값을 처음 값에서 바꾸고 복습 알림을 켰다. 알림 시각이 진하게 보인다. */
    @Test fun speakingSet() = shot(SettingsSamples.speakingSet)

    @Test fun speakingSetDark() = shot(SettingsSamples.speakingSet, dark = true)

    private fun shot(state: SettingsUiState, dark: Boolean = false) = captureRoboImage {
        MontageTheme(darkTheme = dark) { SettingsScreen(state, onIntent = {}, onOpenKeyEntry = {}, onOpenFreeTierNotice = {}, onOpenFlagExport = {}, onOpenAppInfo = {}, onPickQuestionFile = {}) }
    }
}
