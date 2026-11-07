package io.github.nosorae.aiopic.ui.featurehistory

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.libs.haptics.api.HapticEffect
import io.github.nosorae.aiopic.libs.haptics.api.Haptics
import io.github.nosorae.aiopic.ui.design.LocalHaptics
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 지난번에 본 것보다 오른 약점 점수를 처음 볼 때 그 점수 둘레에 작은 축하를 한다(#198). 불꽃이 퍼지는 중인 0.3초 장면을
 * 라이트, 다크, 글자 200% 로 찍는다. 동작 줄이기면 조각 없이 성공 진동만 낸다.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class HistoryCelebrationTest {
    @get:Rule val rule = createComposeRule()

    private val played = mutableListOf<HapticEffect>()
    private val intents = mutableListOf<HistoryIntent>()

    @Test
    fun `198 오른 약점 줄의 축하가 시작될 때 본 표시를 남기라고 알린다`() {
        show(HistorySamples.weaknessRose)
        rule.mainClock.advanceTimeBy(300)

        assertEquals(HistorySamples.weaknessRose.weaknesses.filter { it.rose }.map { HistoryIntent.MarkWeaknessSeen(it) }, intents)
    }

    @Test fun weaknessRose() = shot(dark = false)

    @Test fun weaknessRose_dark() = shot(dark = true)

    @Config(fontScale = 2f)
    @Test
    fun weaknessRose_fontScale200() = shot(dark = false)

    @Test
    fun `198 오른 약점 점수는 작은 축하와 성공 진동을 한 번 낸다`() {
        show(HistorySamples.weaknessRose)
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(listOf(HapticEffect.Success), played)
    }

    @Test
    fun `198 오른 점수가 없으면 축하하지 않는다`() {
        show(HistorySamples.weaknessRose.copy(weaknesses = HistorySamples.weaknessRose.weaknesses.map { it.copy(rose = false) }))
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(emptyList<HapticEffect>(), played)
    }

    @Test
    fun `198 동작 줄이기면 조각 없이 진동만 낸다`() {
        show(HistorySamples.weaknessRose, reduceMotion = true)
        rule.mainClock.advanceTimeBy(300)
        val during = frame()
        rule.mainClock.advanceTimeBy(1_000)

        assertTrue("동작 줄이기인데 조각이 그려진다", during.sameAs(frame()))
        assertEquals(listOf(HapticEffect.Success), played)
    }

    private fun shot(dark: Boolean) {
        show(HistorySamples.weaknessRose, dark)
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage()
    }

    private fun show(state: HistoryUiState, dark: Boolean = false, reduceMotion: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MontageTheme(darkTheme = dark, reduceMotion = reduceMotion) {
                CompositionLocalProvider(LocalHaptics provides Haptics { played += it }) { HistoryScreen(state = state, onOpenSession = {}, onOpenAnswer = {}, onOpenCoach = {}, onIntent = { intents += it }) }
            }
        }
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
}
