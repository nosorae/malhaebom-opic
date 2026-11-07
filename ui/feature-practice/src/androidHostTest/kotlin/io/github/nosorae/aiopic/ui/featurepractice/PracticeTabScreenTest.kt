package io.github.nosorae.aiopic.ui.featurepractice

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 연습 탭은 Play 한 번으로 한 문항을 시작한다. 약한 유형은 읽기 전에는 보이지 않는다. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class PracticeTabScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `Play 를 누르면 한 문항 연습을 연다`() {
        var started = 0
        rule.setContent { MontageTheme { PracticeTabScreen(PracticeTabSamples.weak, onStart = { started++ }, onOpenCoach = {}) } }

        rule.onNodeWithText("Play").performClick()

        assertEquals(1, started)
    }

    @Test
    fun `약한 유형과 점수를 보인다`() {
        rule.setContent { MontageTheme { PracticeTabScreen(PracticeTabSamples.weak, onStart = {}, onOpenCoach = {}) } }

        rule.onNodeWithText("42점").assertExists()
    }

    @Test
    fun `읽기 전에는 모든 유형 안내를 잠깐 보였다 지우지 않는다`() {
        rule.setContent { MontageTheme { PracticeTabScreen(PracticeTabUiState(), onStart = {}, onOpenCoach = {}) } }

        rule.onNodeWithText("모든 유형에서 골랐어요").assertDoesNotExist()
    }
}
