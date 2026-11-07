package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.ui.featureonboarding.start.StartDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRouteTest {
    private val subScreens = listOf(AppRoute.ReportDetail("s"), AppRoute.AnswerReport("a"), AppRoute.KeyEntry("gemini"), AppRoute.FreeTierNotice, AppRoute.Survey, AppRoute.FlagExport, AppRoute.AppInfo)
    private val everyRoute = AppRoute.tabs + subScreens + listOf(AppRoute.Exam("s"), AppRoute.Practice("a"), AppRoute.Coach("c", "q", "Tell me about a trip.", "IH"), AppRoute.StartKeyEntry, AppRoute.StartConsent)

    private fun tabBar(platform: Platform) = everyRoute.filter { it.showsTabBar(platform) }

    @Test
    fun `하단 탭은 모의고사 연습 기록 설정 넷이다`() {
        assertEquals(listOf(AppRoute.ExamTab, AppRoute.PracticeTab, AppRoute.History, AppRoute.Settings), AppRoute.tabs)
        assertEquals(listOf("모의고사", "연습", "기록", "설정"), AppRoute.tabs.map { it.title })
    }

    @Test
    fun `Android 는 최상위 탭 화면에서만 탭을 보인다`() {
        assertEquals(AppRoute.tabs, tabBar(Platform.Android))
    }

    @Test
    fun `iOS 는 리포트 상세 같은 하위 화면에도 탭을 남긴다`() {
        assertEquals(AppRoute.tabs + subScreens, tabBar(Platform.Ios))
    }

    @Test
    fun `시험 진행 녹음 첫 실행 화면은 두 플랫폼 모두 탭이 없다`() {
        val fullScreens = listOf(AppRoute.Exam("s"), AppRoute.Practice("a"), AppRoute.Coach("c", "q", "Tell me about a trip.", "IH"), AppRoute.StartKeyEntry, AppRoute.StartConsent)

        assertEquals(emptyList(), fullScreens.filter { it.showsTabBar(Platform.Android) || it.showsTabBar(Platform.Ios) })
    }

    @Test
    fun `시스템 뒤로가기를 막는 화면은 시험 하나다`() {
        assertEquals(listOf<AppRoute>(AppRoute.Exam("s")), everyRoute.filter { it.blocksBack })
    }

    @Test
    fun `iOS 뒤로 버튼은 하위 화면에만 있고 화면 안 X 로 닫는 시험 연습 설정 서베이와 첫 실행 화면에는 없다`() {
        assertEquals(subScreens - AppRoute.Survey, everyRoute.filter { it.showsBackButtonOnIos })
    }

    @Test
    fun `F1_0 시작 분기 결과마다 첫 화면이 정해진다`() {
        assertEquals(
            listOf(AppRoute.StartKeyEntry, AppRoute.StartConsent, AppRoute.ExamTab),
            listOf(StartDestination.KeyEntry, StartDestination.Consent, StartDestination.Home).map(AppRoute::start),
        )
    }
}
