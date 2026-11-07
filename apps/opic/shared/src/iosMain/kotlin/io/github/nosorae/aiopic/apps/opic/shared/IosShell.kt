package io.github.nosorae.aiopic.apps.opic.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import io.github.nosorae.aiopic.apps.opic.shared.data.opicDatabase
import io.github.nosorae.aiopic.apps.opic.shared.data.recordingsRoot
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.debug.DebugTools
import io.github.nosorae.aiopic.libs.debug.install
import io.github.nosorae.aiopic.libs.duplexaudio.api.IosDuplexAudio
import io.github.nosorae.aiopic.libs.haptics.api.iosHaptics
import io.github.nosorae.aiopic.libs.keyvault.api.KeychainKeyVault
import io.github.nosorae.aiopic.libs.llmgemini.BackgroundUpload
import io.github.nosorae.aiopic.libs.recorder.api.IosRecorder
import io.github.nosorae.aiopic.libs.tts.IosTts
import io.github.nosorae.aiopic.libs.workqueue.api.IosWorkQueue
import io.github.nosorae.aiopic.packs.opicen.OpicEn
import io.github.nosorae.aiopic.ui.design.LocalHaptics
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.featuresettings.theme.ThemeMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS 앱 틀(`ContentView.swift` 의 SwiftUI TabView, NavigationStack)이 목적지마다 Compose 화면을 하나씩 얻어 간다.
 * 탭 표시와 뒤로 버튼은 Swift 가 [AppRoute] 표를 읽어 정한다. 그래프는 앱에 하나다.
 */
class IosShell {
    init {
        // 그래프가 스위치를 읽고 클라이언트를 만들기 전에 설치한다. 릴리스 빌드는 빈 모듈이라 아무 일도 안 한다.
        DebugTools.install()
    }

    private val keyVault = KeychainKeyVault().also(UiTestLaunch::resetIfAsked)

    // 대기열이 그래프보다 먼저 만들어지므로 비우기는 부를 때 그래프에서 찾는다. 대기열은 DB 의 분석 작업 표를 비우기만 하고(#205),
    // 앱이 뒤로 가면 백그라운드 처리 작업으로 이어서 비운다. 앱 틀(iOSApp)이 실행을 마치기 전에 만들어서 등록 시점을 지킨다.
    // 앱이 화면에 없을 때 비우기가 끝나면 리포트 알림을 띄운다(#229).
    private val workQueue = IosWorkQueue(
        drain = { graph.opicStore.drainAndNotify(graph.clock, graph.analysisJobs::drain, visible = { UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive }, notify = ::postOutcome) },
        scope = MainScope(),
        backgroundTask = ANALYSIS_TASK,
        keepDraining = { continuing },
    )

    /**
     * 시스템 진행 표시(iOS 26 이상)가 떠 있는가. 앱 틀(Swift)이 진행 작업을 받으면 true, 닫으면 false 로 둔다(#229).
     * 그동안은 앱이 깨어 있어서 뒤로 간 앱이 받는 30초 안팎이 끝나도 비우기를 이어 간다.
     */
    var continuing: Boolean = false

    // 녹음 분석 요청은 시스템이 맡아 보내서 앱을 내려도 끊기지 않는다(#216). 앱이 꺼진 사이 끝난 전송의 결과는 여기서 다시 받는다.
    private val upload = BackgroundUpload(UPLOAD_SESSION)

    private val graph: AppGraph = createAppGraph(
        settings = AppSettings(
            read = { NSUserDefaults.standardUserDefaults.stringForKey(it) },
            write = { key, value -> NSUserDefaults.standardUserDefaults.setObject(value, forKey = key) },
        ),
        keyVault = keyVault,
        database = opicDatabase(),
        recordingsRoot = recordingsRoot(),
        recorder = IosRecorder().withDebugTools(),
        tts = IosTts(),
        workQueue = workQueue,
        duplexAudio = IosDuplexAudio(),
        gemini = debugGeminiClient(upload = upload),
    ).apply {
        registerDebugPanels(audio = ::audioSessionRows)
        // 앱이 보내는 중에 죽었으면 진행 중으로 남은 작업을 대기로 돌리고 대기열을 깨운다.
        MainScope().launch { analysisJobs.recover() }
        if (UiTestLaunch.storeDemo) MainScope().launch { opicStore.seedStoreDemo(OpicEn.questionPool()) }
    }

    private val haptics = graph.settings.haptics(iosHaptics())

    /** 화면마다 테마를 따로 띄워서, 등급 구슬과 점수 숫자가 한 번 움직였는지는 앱이 떠 있는 동안 여기 하나로 모은다. */
    private val playedMotions = mutableSetOf<String>()

    /** 시작 분기(S0, F1.0). 저장된 키와 동의만 읽어 첫 화면을 [onDecided] 로 넘긴다. 그 전까지 Swift 는 실행 화면과 같은 빈 화면만 둔다. */
    fun decideStart(onDecided: (AppRoute) -> Unit) {
        MainScope().launch { onDecided(AppRoute.start(graph.decideStartUseCase())) }
    }

    /** [onBlockBack] 은 화면이 처리 중이라 뒤로 버튼과 가장자리 스와이프를 숨길 때 true, 다시 보일 때 false 를 받는다. */
    fun controller(
        route: AppRoute,
        onOpen: (AppRoute) -> Unit,
        onExit: () -> Unit,
        onBack: () -> Unit,
        onRestart: (AppRoute) -> Unit,
        onBlockBack: (Boolean) -> Unit,
    ): UIViewController = ComposeUIViewController {
        val themeMode by graph.themeModeStore.themeMode.collectAsState()
        // SwiftUI 가 그리는 탭 바와 내비게이션 바도 같은 모드를 따르게 창의 모드를 바꾼다(F9.1).
        LaunchedEffect(themeMode) { applyToWindows(themeMode) }
        // 동작 줄이기는 화면을 그릴 때 읽는다. 설정을 바꾸면 다음에 다시 그리는 화면부터 따른다.
        MontageTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme()), reduceMotion = UIAccessibilityIsReduceMotionEnabled(), playedMotions = playedMotions) {
            CompositionLocalProvider(LocalHaptics provides haptics) { RouteContent(route, graph, onOpen, onExit, onBack, onRestart, onBlockBack = onBlockBack) }
        }
    }

    /**
     * 분석 진행을 [onChange] 로 넘긴다(#229). 기다리는 작업이 생길 때마다 0 부터 다시 세고, 다 끝나면 null 을 넘긴다.
     * 앱 틀(Swift)이 iOS 26 이상에서 시스템 진행 표시에 쓴다. 첫 분석을 시작할 때 알림 권한을 묻는다. 시스템은 한 번만 묻는다.
     */
    fun watchProgress(onChange: (ProgressNotice?) -> Unit) {
        MainScope().launch {
            graph.opicStore.analyzing().collectLatest { active ->
                if (!active) return@collectLatest onChange(null)
                UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
                graph.opicStore.analysisProgress(graph.clock).collect(onChange)
            }
        }
    }

    /** 사용자가 시스템 진행 표시에서 분석을 멈췄다(#229). 남은 작업을 취소로 끝내고, 리포트는 다시 분석하기를 보인다. */
    fun cancelAnalysis() {
        MainScope().launch { graph.analysisJobs.cancelAll(AnalysisError.Canceled) }
    }

    /** 앱 틀이 시스템에서 받은 백그라운드 전송 완료 처리기. 앱이 꺼진 사이 끝난 전송의 결과를 다 받으면 부른다. */
    fun whenUploadsDelivered(done: () -> Unit) = upload.whenEventsDelivered(done)

    fun showsTabBar(route: AppRoute): Boolean = route.showsTabBar(Platform.Ios)

    private fun applyToWindows(mode: ThemeMode) {
        val style = when (mode) {
            ThemeMode.System -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.Light -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.Dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.filterIsInstance<UIWindow>() }
            .forEach { it.overrideUserInterfaceStyle = style }
    }
}

/** 분석 작업을 이어 비우는 백그라운드 처리 작업의 식별자. Info.plist 의 `BGTaskSchedulerPermittedIdentifiers` 와 같아야 한다. */
private const val ANALYSIS_TASK = "io.github.nosorae.aiopic.analysis"

/** 녹음 분석 요청을 보내는 백그라운드 전송 세션의 이름. 앱에 하나다. */
private const val UPLOAD_SESSION = "io.github.nosorae.aiopic.upload"

/** 끝 알림의 경로를 담는 키. 앱 틀(Swift)이 알림을 누르면 이 값을 [routeFromJson] 으로 읽어 리포트를 연다. */
const val ROUTE_KEY = "route"

private suspend fun postOutcome(outcome: AnalysisOutcome) {
    val title = outcome.title()
    val body = outcome.text()
    val content = UNMutableNotificationContent().apply {
        setTitle(title)
        setBody(body)
        setSound(UNNotificationSound.defaultSound)
        setUserInfo(mapOf<Any?, Any?>(ROUTE_KEY to outcome.routeJson))
    }
    // ponytail: 끝 알림은 하나만 둔다. 새 결과가 앞 알림을 바꾼다. 권한이 없으면 시스템이 띄우지 않는다.
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(UNNotificationRequest.requestWithIdentifier("analysis-done", content, null), null)
}

/** 앱 Swift 의 흔들기(`UIWindow.motionEnded`)가 부른다. 릴리스 빌드는 빈 모듈이라 아무 일도 안 한다. */
fun openDebugTools() = DebugTools.open()

private fun audioSessionRows(): List<Pair<String, String>> {
    val session = AVAudioSession.sharedInstance()
    val route = session.currentRoute
    return listOf(
        "category" to session.category.orEmpty(),
        "mode" to session.mode.orEmpty(),
        "inputs" to route.inputs.joinToString { (it as AVAudioSessionPortDescription).portType.orEmpty() },
        "outputs" to route.outputs.joinToString { (it as AVAudioSessionPortDescription).portType.orEmpty() },
    )
}
