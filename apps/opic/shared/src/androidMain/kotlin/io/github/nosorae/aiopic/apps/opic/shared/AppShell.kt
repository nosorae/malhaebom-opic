package io.github.nosorae.aiopic.apps.opic.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.nosorae.aiopic.libs.haptics.api.androidHaptics
import io.github.nosorae.aiopic.ui.design.LocalHaptics
import io.github.nosorae.aiopic.ui.design.MontageTheme
import io.github.nosorae.aiopic.ui.design.Res
import io.github.nosorae.aiopic.ui.design.icon_normal_ai_review
import io.github.nosorae.aiopic.ui.design.icon_normal_calendar
import io.github.nosorae.aiopic.ui.design.icon_normal_document_text
import io.github.nosorae.aiopic.ui.design.icon_normal_microphone
import io.github.nosorae.aiopic.ui.design.icon_normal_setting
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartDestination
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartUiState
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartViewModel
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.painterResource

/**
 * 시작 분기(S0, F1.0)를 마친 Android 앱. [start] 가 첫 화면을 정하기 전에는 아무것도 그리지 않는다.
 * 그동안 MainActivity 가 같은 [start] 의 [StartUiState.keepSplash] 로 시스템 스플래시를 잡아 둔다.
 * 화면이 쓰는 진동은 이 화면의 View 로 내고, 설정에서 끄면 내지 않는다.
 * [opened] 는 분석 끝 알림을 눌러 연 리포트다(#229). 모의고사 탭에서 시작할 때만 그 위에 쌓는다.
 */
@Composable
fun OpicApp(graph: AppGraph, start: StartViewModel, opened: AppRoute? = null) {
    val state by start.uiState.collectAsState()
    val view = LocalView.current
    val haptics = remember(view) { graph.settings.haptics(androidHaptics(view)) }
    AskNotificationsOnFirstAnalysis(graph)
    CompositionLocalProvider(LocalHaptics provides haptics) {
        state.destination?.let { AppShell(graph, startStack = listOfNotNull(AppRoute.start(it), opened.takeIf { _ -> it == StartDestination.Home })) }
    }
}

/**
 * Android 13 이상에서 첫 분석이나 첫 말하기 연습을 시작할 때 알림 권한을 한 번 묻는다(#229, F16.3). 연습 진행 알림과 정리 끝 알림이 이 권한을 쓴다.
 * 거절해도 분석과 연습은 그대로 돌고 다시 묻지 않는다.
 */
@Composable
private fun AskNotificationsOnFirstAnalysis(graph: AppGraph) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(graph) {
        if (graph.settings.notificationsAsked) return@LaunchedEffect
        graph.awaitNotificationAsk()
        graph.settings.notificationsAsked = true
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Android 앱 틀. 하단 탭(Material 3 Expressive ShortNavigationBar)과 예측 뒤로가기(Navigation 3)를 그린다.
 * 탭을 보일지와 뒤로가기를 막을지는 [AppRoute] 표가 정하고, Android 플랫폼 코드는 이 파일 하나다(ADR 0018).
 *
 * 백스택은 하나다. Android 는 하위 화면에서 탭을 숨겨서 탭을 옮기는 건 늘 탭 화면에 있을 때뿐이라 탭마다 스택을 둘 일이 없다.
 * 모의고사가 아닌 탭은 모의고사 탭 위에 쌓아 뒤로가기로 모의고사 탭에 돌아오게 한다. [startStack] 은 테스트가 시작 화면을 정할 때만 쓴다.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppShell(graph: AppGraph, modifier: Modifier = Modifier, startStack: List<AppRoute> = listOf(AppRoute.ExamTab)) {
    val backStack = rememberSaveable(saver = backStackSaver) { startStack.toMutableStateList() }
    val top = backStack.last()
    val themeMode by graph.themeModeStore.themeMode.collectAsState()
    val dark = themeMode.isDark(isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    // 시스템과 다른 화면 모드를 고르면 상태 바와 내비게이션 바 아이콘 색도 앱 테마에 맞춘다.
    LaunchedEffect(activity, dark) {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    val context = LocalContext.current
    // ponytail: 앱을 띄울 때 한 번 읽는다. 앱이 떠 있는 동안 설정을 바꾸면 다음 실행부터 반영된다. 바로 따라가야 하면 ContentObserver 를 단다.
    val reduceMotion = remember(context) { context.animationsOff() }
    MontageTheme(darkTheme = dark, reduceMotion = reduceMotion) {
        Scaffold(
            modifier = modifier,
            // 인셋은 화면(MontageScreen)이 맡는다. 탭 바가 있을 때만 그 높이를 여백으로 주고 소비한다.
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (top.showsTabBar(Platform.Android)) {
                    ShortNavigationBar {
                        AppRoute.tabs.forEach { tab ->
                            ShortNavigationBarItem(
                                selected = top == tab,
                                onClick = { backStack.selectTab(tab) },
                                icon = { Icon(painterResource(tabIcon(tab)), contentDescription = null) },
                                label = { Text(tab.title) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavDisplay(
                backStack = backStack,
                // 연습 중 화면에서 뒤로 가면 들어온 곳과 상관없이 연습 탭으로 간다. 연습이 계속된다는 안내가 연습 탭 카드에 있다(F16.4).
                onBack = { if (backStack.last() == AppRoute.Speaking) backStack.selectTab(AppRoute.PracticeTab) else backStack.removeAt(backStack.lastIndex) },
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = { route ->
                    NavEntry(route) {
                        // 시험 중과 화면이 처리 중일 때는 시스템 뒤로가기(예측 뒤로가기 포함)가 화면을 벗어나지 않게 받아서 버린다.
                        var busy by remember { mutableStateOf(false) }
                        BackHandler(enabled = route.blocksBack || busy) {}
                        RouteContent(
                            route = route,
                            graph = graph,
                            onOpen = { if (it in AppRoute.tabs) backStack.selectTab(it) else backStack.add(it) },
                            onExit = { backStack.retainAll { it == AppRoute.ExamTab } },
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onRestart = { first -> backStack.replaceAll(listOf(first)) },
                            onBlockBack = { busy = it },
                        )
                    }
                },
            )
        }
    }
}

/** 개발자 옵션이나 접근성 설정에서 애니메이션을 끄면 애니메이터 배율이 0 이 된다. 이때 움직임 부품은 끝 장면을 바로 보인다. */
internal fun Context.animationsOff(): Boolean = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun SnapshotStateList<AppRoute>.selectTab(tab: AppRoute) = replaceAll(listOfNotNull(AppRoute.ExamTab, tab.takeIf { it != AppRoute.ExamTab }))

/** 비우기와 채우기를 한 번에 반영한다. 시작 분기를 다시 하는 코루틴이 다른 스레드에서 이어지면 그 사이 화면이 빈 백스택을 읽는다(#194). */
private fun SnapshotStateList<AppRoute>.replaceAll(next: List<AppRoute>) = Snapshot.withMutableSnapshot {
    clear()
    addAll(next)
}

private fun tabIcon(tab: AppRoute) = when (tab) {
    AppRoute.PracticeTab -> Res.drawable.icon_normal_microphone
    AppRoute.ExpressionsTab -> Res.drawable.icon_normal_ai_review
    AppRoute.History -> Res.drawable.icon_normal_calendar
    AppRoute.Settings -> Res.drawable.icon_normal_setting
    else -> Res.drawable.icon_normal_document_text
}

private val backStackSaver = listSaver<SnapshotStateList<AppRoute>, String>(
    save = { stack -> stack.map { Json.encodeToString(AppRoute.serializer(), it) } },
    restore = { saved -> saved.map { Json.decodeFromString(AppRoute.serializer(), it) }.toMutableStateList() },
)
