package io.github.nosorae.aiopic.apps.opic.shared

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Android 앱 틀을 그래프째 띄워 탭 표시와 뒤로가기를 Activity 의 뒤로가기 디스패처로 확인한다. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class AppShellTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val settings = AppSettings.inMemory()

    private val graph by lazy { testAppGraph(settings) }

    private fun start(vararg stack: AppRoute) = rule.setContent { AppShell(graph, startStack = stack.toList()) }

    /** 시험 화면은 회차를 DB 에서 읽은 뒤에 서베이를 그린다. */
    private fun waitForExam() = rule.waitUntil(5_000) { rule.onAllNodes(hasText("지금 하는 일은 어느 쪽에", substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** 상단 X 를 누르고 확인 대화상자의 나가기를 누른다. */
    private fun exitExam() {
        rule.onNodeWithContentDescription("나가기").performClick()
        rule.onNodeWithText("나가기").performClick()
    }

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `애니메이션 배율을 끄면 동작 줄이기로 본다`() {
        val resolver = rule.activity.contentResolver
        assertFalse(rule.activity.animationsOff())

        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

        assertTrue(rule.activity.animationsOff())
    }

    @Test
    fun `최상위 탭 화면에서는 하단 탭이 보인다`() {
        start(AppRoute.ExamTab)

        rule.onNodeWithText("기록").assertIsDisplayed()
    }

    @Test
    fun `Android 하위 화면에서는 하단 탭을 숨긴다`() {
        start(AppRoute.ExamTab, AppRoute.ReportDetail("s"))

        rule.onNodeWithText("기록").assertDoesNotExist()
    }

    @Test
    fun `하위 화면에서 뒤로가기를 누르면 모의고사 탭으로 돌아가고 탭이 다시 보인다`() {
        start(AppRoute.ExamTab, AppRoute.ReportDetail("s"))

        pressBack()

        rule.onNodeWithText("기록").assertIsDisplayed()
    }

    @Test
    fun `탭을 누르면 그 탭 화면으로 가고 뒤로가기로 모의고사 탭에 돌아온다`() {
        start(AppRoute.ExamTab)
        rule.onNodeWithText("설정").performClick()
        rule.onNodeWithTag("exam-start").assertDoesNotExist()

        pressBack()

        rule.onNodeWithTag("exam-start").assertIsDisplayed()
    }

    @Test
    fun `네 탭을 차례로 누르면 탭마다 제 화면이 보인다`() {
        start(AppRoute.ExamTab)

        rule.onNodeWithText("연습").performClick()
        // 탭 맨 위에 말하기 연습 카드와 오늘 복습 카드가 있어 Play 는 스크롤 아래에 있다(F16.1).
        rule.onNodeWithText("Play").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("기록").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("아직 기록이 없습니다", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("설정").performClick()
        rule.onNodeWithText("분석 모델").assertIsDisplayed()
        rule.onNodeWithText("모의고사").performClick()
        rule.onNodeWithTag("exam-start").assertIsDisplayed()
    }

    @Test
    fun `연습 탭의 Play 를 누르면 탭 없는 한 문항 연습이 열리고 뒤로가기로 연습 탭에 돌아온다`() {
        start(AppRoute.ExamTab, AppRoute.PracticeTab)

        rule.onNodeWithText("Play").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("한 문항 연습")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("기록").assertDoesNotExist()
        pressBack()
        rule.onNodeWithText("기록").assertIsDisplayed()
    }

    @Test
    fun `모의고사 탭에서 시작을 누르면 탭 없는 시험 화면이 열린다`() {
        start(AppRoute.ExamTab)

        rule.onNodeWithTag("exam-start").performClick()
        waitForExam()

        rule.onNodeWithText("기록").assertDoesNotExist()
        rule.onNodeWithContentDescription("나가기").assertIsDisplayed()
    }

    @Test
    fun `시험 중 뒤로가기를 누르면 시험 화면에 머문다`() {
        start(AppRoute.ExamTab, AppRoute.Exam("s"))
        waitForExam()

        pressBack()

        rule.onNodeWithContentDescription("나가기").assertIsDisplayed()
    }

    @Test
    fun `시험 화면의 나가기 X 를 누르고 확인하면 모의고사 탭으로 돌아간다`() {
        start(AppRoute.ExamTab, AppRoute.Exam("s"))
        waitForExam()

        exitExam()

        // 저장하고 나간 회차가 남아 있어 모의고사 탭에 이어 보기가 생긴다.
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("이어 보기")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `시험 화면의 나가기 확인에서 취소를 누르면 시험에 머문다`() {
        start(AppRoute.ExamTab, AppRoute.Exam("s"))
        waitForExam()

        rule.onNodeWithContentDescription("나가기").performClick()
        rule.onNodeWithText("취소").performClick()

        rule.onNodeWithText("모의고사를 나갈까요?").assertDoesNotExist()
        rule.onNodeWithText("지금 하는 일은 어느 쪽에", substring = true).assertExists()
    }

    @Test
    fun `F3 나갔던 시험이 있으면 모의고사 탭의 이어 보기가 같은 회차를 연다`() {
        start(AppRoute.ExamTab, AppRoute.Exam("s"))
        waitForExam()
        rule.onNodeWithText("지금 하는 일은 어느 쪽에", substring = true).performScrollTo().assertIsDisplayed()
        exitExam()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("이어 보기")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("이어 보기").performClick()
        waitForExam()

        rule.onNodeWithContentDescription("나가기").assertIsDisplayed()
        assertEquals(listOf("s"), runBlocking { graph.examSessionSource.sessions().first() }.map { it.id })
    }

    @Test
    fun `F17_7 표현 탭에서 시작한 연습의 정리 화면에서 다 봤어요를 누르면 연습 탭으로 가고 뒤로가기로 모의고사 탭에 돌아온다`() {
        start(AppRoute.ExamTab, AppRoute.ExpressionsTab, AppRoute.SpeakingSummary("p"))
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("다 봤어요")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("다 봤어요").performClick()

        rule.onNodeWithText("Play").performScrollTo().assertIsDisplayed()
        pressBack()
        rule.onNodeWithTag("exam-start").assertIsDisplayed()
    }

    @Test
    fun `F8_3 설정의 틀림 표시 내보내기 줄을 누르면 내보내기 화면이 열린다`() {
        start(AppRoute.ExamTab, AppRoute.Settings)

        rule.onNodeWithText("틀림 표시 내보내기").performScrollTo().performClick()

        rule.onNodeWithText("JSON 복사").assertExists()
    }

    @Test
    fun `F9_1 설정에서 다크를 고르면 앱 틀이 보는 화면 모드가 바로 다크가 된다`() {
        start(AppRoute.ExamTab, AppRoute.Settings)

        rule.onNodeWithText("다크").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(ThemeMode.Dark, settings.themeMode.value)
    }

    @Test
    fun `F1_2 설정의 제공사 키 줄을 누르면 키 입력 화면이 열리고 뒤로가기로 설정에 돌아온다`() {
        start(AppRoute.ExamTab, AppRoute.Settings)

        rule.onNodeWithText("Gemini").performScrollTo().performClick()
        rule.onNodeWithText("API 키 넣기").assertIsDisplayed()
        pressBack()

        rule.onNodeWithText("분석 모델").assertIsDisplayed()
    }

    @Test
    fun `F1_3 설정의 무료 등급 안내 보기는 음성 전송 동의 화면으로 연결한다`() {
        start(AppRoute.ExamTab, AppRoute.Settings)

        rule.onNodeWithText("무료 등급 안내 보기").performScrollTo().performClick()

        rule.onNodeWithText("음성 전송 동의").assertIsDisplayed()
    }

    @Test
    fun `설정의 개인정보처리방침 줄을 누르면 저장소의 방침 문서를 연다`() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                AppShell(testAppGraph(settings), startStack = listOf(AppRoute.ExamTab, AppRoute.Settings))
            }
        }

        rule.onNodeWithText("개인정보처리방침").performScrollTo().performClick()

        assertEquals(listOf("https://github.com/nosorae/malhaebom-opic/blob/main/docs/privacy-policy.md"), opened)
    }
}
