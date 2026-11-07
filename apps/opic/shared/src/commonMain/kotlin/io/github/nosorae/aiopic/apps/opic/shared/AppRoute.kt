package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.ui.featureonboarding.start.StartDestination
import kotlinx.serialization.Serializable

enum class Platform { Android, Ios }

/** 앱 틀이 화면을 어떻게 대우하는지. 화면 모듈은 이 값을 모른다. [Start] 는 탭도 뒤로 버튼도 없는 첫 실행 화면이다. */
private enum class Chrome { TopLevel, Sub, FullScreen, Start }

/**
 * 앱 틀의 화면 경로 표. 하단 탭 표시, 뒤로가기 가로채기, iOS 뒤로 버튼이 이 표 한 곳에서만 갈린다(ADR 0018).
 * 화면 모듈은 플랫폼도 탭도 모르고, 두 플랫폼 앱 틀(Android `AppShell`, iOS `ContentView.swift`)이 이 표를 읽는다.
 *
 * Swift 의 NavigationStack 경로로도 쓰므로 interface 가 아니라 class 다. Kotlin 클래스는 Swift 에서 Hashable 이다.
 *
 * 키와 등록을 화면 모듈이 아니라 이 표 한 곳에 두는 이유는 ADR 0034 에 있다.
 */
@Serializable
sealed class AppRoute {
    /** 모의고사 탭(S3). 이어 보기, 시작, 다시 보기. 앱을 켜면 처음 보이는 탭이다. */
    @Serializable data object ExamTab : AppRoute()

    /** 연습 탭. Play 한 번으로 [Practice] 를 열어 바로 질문을 읽는다. */
    @Serializable data object PracticeTab : AppRoute()

    @Serializable data object History : AppRoute()

    @Serializable data object Settings : AppRoute()

    @Serializable data class ReportDetail(val sessionId: String) : AppRoute()

    /** 답변 하나의 리포트. 회차 리포트의 답변 줄과 한 문항 연습의 Next 가 연다. */
    @Serializable data class AnswerReport(val answerId: String) : AppRoute()

    /** 모의고사. 모의고사 탭이 진행 중인 회차 id 를 넣으면 이어 보기, 새 id 면 새 회차다(F3). */
    @Serializable data class Exam(val sessionId: String) : AppRoute()

    /**
     * 한 문항 연습. 녹음하는 화면이라 탭이 없지만 시험이 아니라서 뒤로가기는 막지 않는다. 녹음 중 뒤로가기는 화면이 받아 나갈지 묻는다.
     * 연습 탭의 Play 로 열리고 열리자마자 질문을 읽는다. [answerId] 는 들어올 때마다 새로 만든다.
     * 스크립트 화면의 "이 스크립트로 연습" 은 [questionId] 질문과 [script] 글을 실어 연다(F14.5).
     */
    @Serializable data class Practice(val answerId: String, val questionId: String? = null, val script: String? = null) : AppRoute()

    /**
     * 코치 대화(F11, F12). 시작 시트가 질문(id 와 글)과 목표 등급을 정해 연다. 질문 글을 같이 실어 다시 열 때 문항 풀에서 찾지 않는다. 같은 [sessionId] 로 다시 열면 저장된 세션을 이어 간다(F11.8).
     * 말하는 화면이라 탭이 없고 화면 안 X 로 닫는다. 세션은 저장돼 있어 뒤로가기도 막지 않는다.
     */
    @Serializable data class Coach(val sessionId: String, val questionId: String, val question: String, val targetGrade: String) : AppRoute()

    /** 코치 세션의 스크립트(F14). 코치 대화가 끝나면 대화 화면 자리에 열리고, 기록 탭의 스크립트 줄로 다시 연다. 질문 글은 다듬기 요청에 쓴다. */
    @Serializable data class CoachScript(val sessionId: String, val questionId: String, val question: String) : AppRoute()

    /** 설정의 제공사별 키 관리에서 여는 키 입력. [provider] 는 제공사 이름(`gemini`)이다. */
    @Serializable data class KeyEntry(val provider: String) : AppRoute()

    /** 설정의 무료 등급 안내. 음성 전송 동의 화면을 그대로 연다(F1.3). */
    @Serializable data object FreeTierNotice : AppRoute()

    /** 설정의 서베이. 모의고사 서베이와 같은 흐름으로 저장한 답을 보고 바꾼다. 바꾼 답이 있으면 뒤로가기를 화면이 받아 나갈지 묻는다. */
    @Serializable data object Survey : AppRoute()

    /** 설정의 틀림 표시 내보내기(F8.3). */
    @Serializable data object FlagExport : AppRoute()

    /** 설정의 앱 정보와 라이선스 고지(ADR 0018). */
    @Serializable data object AppInfo : AppRoute()

    /** 첫 실행의 키 입력(S1). 설정에서 여는 [KeyEntry] 와 달리 탭 위에 쌓지 않고 혼자 뜨며, 끝나면 시작 분기를 다시 한다. */
    @Serializable data object StartKeyEntry : AppRoute()

    /** 첫 실행의 음성 전송 동의(S2). 동의해야 모의고사 탭으로 간다(F1.3). */
    @Serializable data object StartConsent : AppRoute()

    private val chrome: Chrome
        get() = when (this) {
            ExamTab, PracticeTab, History, Settings -> Chrome.TopLevel
            is ReportDetail, is AnswerReport, is CoachScript, is KeyEntry, FreeTierNotice, Survey, FlagExport, AppInfo -> Chrome.Sub
            is Exam, is Practice, is Coach -> Chrome.FullScreen
            StartKeyEntry, StartConsent -> Chrome.Start
        }

    val title: String
        get() = when (this) {
            ExamTab -> "모의고사"
            PracticeTab -> "연습"
            History -> "기록"
            Settings -> "설정"
            is ReportDetail -> "회차 리포트"
            is AnswerReport -> "답변 리포트"
            is Exam -> "모의고사"
            is Practice -> "한 문항 연습"
            is Coach -> "코치와 스크립트 만들기"
            is CoachScript -> "스크립트"
            is KeyEntry, StartKeyEntry -> "API 키 넣기"
            FreeTierNotice, StartConsent -> "음성 전송 동의"
            Survey -> "서베이"
            FlagExport -> "틀림 표시 내보내기"
            AppInfo -> "앱 정보와 라이선스"
        }

    /** 시스템 뒤로가기로 나가면 안 되는 화면. 시험 중에는 저장하고 나가는 화면 안 버튼으로만 나간다. */
    val blocksBack: Boolean get() = this is Exam

    /** Android 는 탭 화면에서만, iOS 는 하위 화면에도 탭을 보인다. 시험, 녹음, 첫 실행 화면은 두 플랫폼 모두 탭이 없다. */
    fun showsTabBar(platform: Platform): Boolean = when (chrome) {
        Chrome.TopLevel -> true
        Chrome.Sub -> platform == Platform.Ios
        Chrome.FullScreen, Chrome.Start -> false
    }

    /**
     * iOS 내비게이션 바(뒤로 버튼과 가장자리 스와이프)를 보일지. Android 는 시스템 뒤로가기라 그리는 버튼이 없다.
     * 시험, 연습, 설정의 서베이는 화면 안 X 로 닫는다. 나가면 잃는 것이 있을 때 먼저 묻는데 iOS 뒤로 버튼은 화면이 가로챌 수 없어서다.
     */
    val showsBackButtonOnIos: Boolean get() = chrome == Chrome.Sub && this != Survey

    companion object {
        /** 하단 탭 순서. 값으로 두면 하위 object 초기화와 순환해 null 이 들어가므로 읽을 때 만든다. */
        val tabs: List<AppRoute> get() = listOf(ExamTab, PracticeTab, History, Settings)

        /** 시작 분기(S0, F1.0) 결과의 첫 화면. 키 입력과 동의는 탭 위가 아니라 백스택 맨 아래에 혼자 둔다. */
        fun start(destination: StartDestination): AppRoute = when (destination) {
            StartDestination.KeyEntry -> StartKeyEntry
            StartDestination.Consent -> StartConsent
            StartDestination.Home -> ExamTab
        }
    }
}
