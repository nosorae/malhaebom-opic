package io.github.nosorae.aiopic.ui.featurelive

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 스크린리더가 목표 등급 칩의 선택과 펼치는 줄의 펼침 상태를 읽는지 본다(ui-quality 4절 5번). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class LiveCoachAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun expanded(open: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (open) "펼침" else "접힘")

    /** 목소리 띠가 계속 돌아 시계를 멈추고 프레임을 손으로 넘긴다. */
    private fun conversation() {
        rule.mainClock.autoAdvance = false
        rule.setContent { MontageTheme { LiveCoachScreen(LiveCoachSamples.exploring, onIntent = {}, onExit = {}) } }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun SemanticsNodeInteraction.toggle() {
        performClick()
        rule.mainClock.advanceTimeBy(500)
    }

    @Test
    fun `목표 등급 칩은 고른 것만 선택됨으로 읽힌다`() {
        rule.setContent { MontageTheme { TargetGradeChips(listOf("IH", "AL"), selected = "IH", onSelect = {}) } }

        rule.onNodeWithText("IH").assertIsOn()
        rule.onNodeWithText("AL").assertIsOff()
    }

    @Test
    fun `소재 메모 요약 줄은 펼침 상태를 읽히고 누르면 바뀐다`() {
        conversation()
        val row = rule.onNode(hasText("소재 메모와 표현 카드", substring = true) and hasClickAction())
        row.assert(expanded(false))

        row.toggle()

        row.assert(expanded(true))
    }

    @Test
    fun `세션 비용 줄은 펼침 상태를 읽히고 누르면 바뀐다`() {
        conversation()
        val row = rule.onNode(hasText("이번 세션 약", substring = true) and hasClickAction())
        row.assert(expanded(false))

        row.toggle()

        row.assert(expanded(true))
    }

    @Test
    fun `긴 질문은 펼침 상태를 읽히고 누르면 바뀐다`() {
        conversation()
        val row = rule.onNode(hasText("Tell me about a memorable trip", substring = true) and hasClickAction())
        row.assert(expanded(false))

        row.toggle()

        row.assert(expanded(true))
    }
}
