package io.github.nosorae.aiopic.ui.featurehistory

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nosorae.aiopic.engine.answer.model.AnswerAnalysis
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 기록이 찬 상태는 라이트, 다크, 글자 200% 를 찍고 연습만 고른 상태와 빈 상태를 한 장씩 찍는다. 입력은 미리보기와 같은 [HistorySamples] 다.
 * 테스트 시계를 멈추고 2.5초 넘겨 등급 구슬이 멈춘 끝 장면을 찍는다. 동작 줄이기면 첫 프레임이 끝 장면과 같은지도 본다.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class HistoryScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun filled() = shot(HistorySamples.filled)

    @Test fun filled_dark() = shot(HistorySamples.filled, dark = true)

    @Config(fontScale = 2f)
    @Test
    fun filled_fontScale200() = shot(HistorySamples.filled)

    @Test fun empty() = shot(HistorySamples.empty)

    @Test fun abandoned() = shot(HistorySamples.abandoned)

    @Test
    fun filled_practiceFilter() {
        show(HistorySamples.filled)
        rule.onNodeWithTag("history-filter-practice").performClick()
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun `F7_4 연습 줄은 유형과 점수, 분석 중, 분석 실패를 보이고 누르면 그 답변을 연다`() {
        var opened: String? = null
        show(HistorySamples.filled, onOpenAnswer = { opened = it })
        rule.onNodeWithText("과거 경험 연습").assertExists()
        rule.onNodeWithText("72점").assertExists()
        rule.onNodeWithContentDescription("분석 중").assertExists()
        rule.onNodeWithText("분석 실패").assertExists()
        rule.onNodeWithText("모의고사 3회차").assertExists()

        rule.onNodeWithText("과거 경험 연습").performClick()
        assertEquals("p2", opened)
    }

    @Test
    fun `F7_4 연습 필터를 고르면 회차 줄과 등급 눈금이 빠진다`() {
        show(HistorySamples.filled)
        rule.onNodeWithTag("history-filter-practice").performClick()
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.onAllNodesWithTag("history-session").assertCountEquals(0)
        rule.onAllNodesWithTag("history-practice").assertCountEquals(3)
        rule.onNodeWithText("회차별 예상 등급").assertDoesNotExist()

        rule.onNodeWithTag("history-filter-exam").performClick()
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.onAllNodesWithTag("history-practice").assertCountEquals(0)
    }

    @Test
    fun `F7_4 연결을 기다리는 연습은 연결되면 분석한다고 보인다`() {
        val row = HistoryPractice("p", "습관", null, AnswerAnalysis.Pending, JobState.WaitingNetwork)
        show(HistoryUiState(loading = false, entries = listOf(row)))
        rule.onNodeWithText("인터넷이 연결되면 바로 분석합니다").assertExists()
    }

    @Test
    fun `한 종류만 있으면 필터를 보이지 않는다`() {
        show(HistoryUiState(loading = false, entries = HistorySamples.filled.sessions))
        rule.onNodeWithTag("history-filter-all").assertDoesNotExist()
    }

    @Test
    fun `등급을 받지 못한 회차는 등급 실패다`() {
        val row = HistorySession(ExamSession(id = "s9", status = ExamStatus.Finished, gradeError = AnalysisError.Retryable), emptyList(), answered = 12, analyzed = 12)
        show(HistoryUiState(loading = false, entries = listOf(row)))
        rule.onNodeWithText("등급 실패").assertExists()
    }

    @Test
    fun `최근 회차 등급 구슬이 눈금 위로 미끄러져 멈춘다`() {
        show(HistorySamples.filled)
        val first = frame()
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertFalse("움직이지 않는다", first.sameAs(frame()))
    }

    @Test
    fun `동작 줄이기면 등급 구슬과 약점 막대가 처음부터 끝 장면이다`() {
        show(HistorySamples.filled, reduceMotion = true)
        val first = frame()
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertTrue("동작 줄이기인데 움직인다", first.sameAs(frame()))
    }

    @Test
    fun `답변이 모두 분석되고 등급만 기다리는 회차는 등급 내는 중이다`() {
        val row = HistorySession(ExamSession(id = "s9", status = ExamStatus.Finished), emptyList(), answered = 12, analyzed = 12)
        show(HistoryUiState(loading = false, entries = listOf(row)))
        rule.onNodeWithText("등급 내는 중").assertExists()
        rule.onNodeWithText("분석 12/12").assertDoesNotExist()
    }

    @Test
    fun `미완료로 끝난 회차는 미완료로 보이고 답이 없으면 등급을 기다리지 않는다`() {
        val row = HistorySession(ExamSession(id = "s9", status = ExamStatus.Abandoned), emptyList(), number = 3)
        show(HistoryUiState(loading = false, entries = listOf(row)))
        rule.onNodeWithText("모의고사 3회차, 미완료").assertExists()
        rule.onNodeWithText("등급 내는 중").assertDoesNotExist()
        rule.onNodeWithText("분석 대기 중").assertDoesNotExist()
    }

    private fun show(state: HistoryUiState, dark: Boolean = false, reduceMotion: Boolean = false, onOpenAnswer: (String) -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MontageTheme(darkTheme = dark, reduceMotion = reduceMotion) { HistoryScreen(state = state, onOpenSession = {}, onOpenAnswer = onOpenAnswer, onOpenCoach = {}, onIntent = {}) }
        }
    }

    private fun shot(state: HistoryUiState, dark: Boolean = false) {
        show(state, dark)
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.onRoot().captureRoboImage()
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private companion object {
        const val SETTLE_MILLIS = 2_500L
    }
}
