package io.github.nosorae.aiopic.apps.opic.shared

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nosorae.aiopic.ui.design.MontageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 모든 경로가 제 화면 모듈의 Route 를 그리는지 본다. 제목만 그리던 임시 화면에는 없는, 화면마다 고유한 문구로 확인한다. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class RouteContentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** 경로를 더하면 이 when 이 컴파일되지 않아 기대 문구를 함께 적게 된다. */
    private fun screenText(route: AppRoute): String = when (route) {
        AppRoute.ExamTab -> "아직 마친 회차가 없습니다"
        AppRoute.PracticeTab -> "질문이 끝나면 바로 녹음돼요"
        AppRoute.History -> "아직 기록이 없습니다"
        AppRoute.Settings -> "분석 모델"
        is AppRoute.ReportDetail -> "이 회차를 찾을 수 없습니다."
        is AppRoute.AnswerReport -> "이 답변을 찾을 수 없습니다."
        is AppRoute.Exam -> "지금 하는 일은 어느 쪽에"
        AppRoute.Survey -> "지금 하는 일은 어느 쪽에"
        is AppRoute.Practice -> "피드백"
        is AppRoute.Coach -> "실시간 음성 전송 동의"
        is AppRoute.CoachScript -> "다시 시도"
        is AppRoute.KeyEntry, AppRoute.StartKeyEntry -> "키 확인하고 저장"
        AppRoute.FreeTierNotice, AppRoute.StartConsent -> "동의하고 계속하기"
        AppRoute.FlagExport -> "아직 \"틀림\"을 누른 교정이 없습니다"
        AppRoute.AppInfo -> "Montage by Wantedlab"
    }

    private fun assertDraws(route: AppRoute) {
        val graph = testAppGraph()
        rule.setContent { MontageTheme { RouteContent(route, graph, onOpen = {}, onExit = {}, onBack = {}, onRestart = {}) } }

        rule.waitUntil(5_000) { rule.onAllNodes(hasText(screenText(route), substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun examTab() = assertDraws(AppRoute.ExamTab)

    @Test fun practiceTab() = assertDraws(AppRoute.PracticeTab)

    @Test fun history() = assertDraws(AppRoute.History)

    @Test fun settings() = assertDraws(AppRoute.Settings)

    @Test fun reportDetail() = assertDraws(AppRoute.ReportDetail("s"))

    @Test fun answerReport() = assertDraws(AppRoute.AnswerReport("a"))

    @Test fun exam() = assertDraws(AppRoute.Exam("s"))

    @Test fun practice() = assertDraws(AppRoute.Practice("a"))

    @Test fun coach() = assertDraws(AppRoute.Coach("c", "q", "Tell me about a trip.", "IH"))

    @Test fun coachScript() = assertDraws(AppRoute.CoachScript("c", "q", "Tell me about a trip."))

    @Test fun keyEntry() = assertDraws(AppRoute.KeyEntry("gemini"))

    @Test fun freeTierNotice() = assertDraws(AppRoute.FreeTierNotice)

    @Test fun survey() = assertDraws(AppRoute.Survey)

    @Test fun flagExport() = assertDraws(AppRoute.FlagExport)

    @Test fun appInfo() = assertDraws(AppRoute.AppInfo)

    @Test fun startKeyEntry() = assertDraws(AppRoute.StartKeyEntry)

    @Test fun startConsent() = assertDraws(AppRoute.StartConsent)
}
