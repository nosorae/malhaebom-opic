package io.github.nosorae.aiopic.apps.opic.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nosorae.aiopic.libs.debug.DebugTools
import io.github.nosorae.aiopic.ui.featureexam.ExamRoute
import io.github.nosorae.aiopic.ui.featureexam.SurveyRoute
import io.github.nosorae.aiopic.ui.featurehistory.HistoryRoute
import io.github.nosorae.aiopic.ui.featurehome.HomeRoute
import io.github.nosorae.aiopic.ui.featurelive.CoachStartRoute
import io.github.nosorae.aiopic.ui.featurelive.LiveCoachRoute
import io.github.nosorae.aiopic.ui.featureonboarding.consent.ConsentRoute
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyEntryRoute
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyEntryViewModel
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyProvider
import io.github.nosorae.aiopic.ui.featurepractice.PracticeRoute
import io.github.nosorae.aiopic.ui.featurepractice.PracticeTabRoute
import io.github.nosorae.aiopic.ui.featurereport.AnswerReportRoute
import io.github.nosorae.aiopic.ui.featurereport.SessionReportRoute
import io.github.nosorae.aiopic.ui.featuresettings.SettingsRoute
import io.github.nosorae.aiopic.ui.featuresettings.export.FlagExportRoute
import io.github.nosorae.aiopic.ui.featuresettings.info.AppInfoRoute
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 경로마다 그릴 화면. 두 플랫폼 앱 틀이 같이 쓴다. [onOpen] 은 지금 탭의 백스택에 화면을 쌓고,
 * [onExit] 은 시험처럼 뒤로가기를 막은 화면이 스스로 나갈 때 탭의 첫 화면까지 돌아간다.
 * [onBack] 은 키 저장, 동의처럼 일을 마친 하위 화면이 한 칸 돌아갈 때 쓴다.
 * [onRestart] 는 첫 실행 화면(S1, S2)을 마쳤을 때 백스택 전체를 시작 분기를 다시 한 첫 화면 하나로 바꾼다.
 * [onBlockBack] 은 화면이 처리 중이라 시스템 뒤로가기와 iOS 가장자리 스와이프를 막아야 할 때 true, 풀 때 false 를 받는다.
 *
 * ViewModel 은 경로 값을 키로 만든다. 같은 화면 모듈이라도 인자가 다르면 다른 ViewModel 이다.
 */
@Composable
internal fun RouteContent(
    route: AppRoute,
    graph: AppGraph,
    onOpen: (AppRoute) -> Unit,
    onExit: () -> Unit,
    onBack: () -> Unit,
    onRestart: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    onBlockBack: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val restart: () -> Unit = { scope.launch { onRestart(AppRoute.start(graph.decideStartUseCase())) } }
    // 코치 시작 시트는 연습 탭과 기록 탭 위에 뜨는 창이라 경로로 쌓지 않는다(F10.1).
    var coachOpen by rememberSaveable { mutableStateOf(false) }
    if (coachOpen) {
        CoachStartRoute(
            viewModel = viewModel { graph.coachStartViewModel },
            // 질문은 한 문항 연습과 같이 가장 약한 유형에서 고른다(F4.1). 낼 질문이 없으면 시트만 닫는다.
            onStart = {
                scope.launch {
                    val pick = graph.pickPracticeQuestionUseCase()
                    coachOpen = false
                    pick?.let { onOpen(AppRoute.Coach(newId(), it.question.id, it.question.text, graph.settings.targetGrade.value)) }
                }
            },
            onDismiss = { coachOpen = false },
        )
    }
    when (route) {
        AppRoute.ExamTab -> HomeRoute(
            viewModel = viewModel { graph.homeViewModel },
            // 이어 보기는 진행 중인 회차 id 를 넘기고, 시작은 null 이라 새 회차를 연다(F3).
            onExamClick = { onOpen(AppRoute.Exam(it ?: newId())) },
            onSessionClick = { onOpen(AppRoute.ReportDetail(it)) },
            modifier = modifier,
        )

        AppRoute.PracticeTab -> PracticeTabRoute(
            viewModel = viewModel { graph.practiceTabViewModel },
            onStart = { onOpen(AppRoute.Practice(newId())) },
            onOpenCoach = { coachOpen = true },
            modifier = modifier,
        )

        AppRoute.History -> HistoryRoute(
            viewModel = viewModel { graph.historyViewModel },
            onOpenSession = { onOpen(AppRoute.ReportDetail(it)) },
            onOpenAnswer = { onOpen(AppRoute.AnswerReport(it)) },
            onOpenCoach = { coachOpen = true },
            modifier = modifier,
        )

        AppRoute.Settings -> SettingsRoute(
            viewModel = viewModel { graph.settingsViewModel },
            onOpenKeyEntry = { onOpen(AppRoute.KeyEntry(it.id)) },
            onOpenFreeTierNotice = { onOpen(AppRoute.FreeTierNotice) },
            onOpenFlagExport = { onOpen(AppRoute.FlagExport) },
            onOpenAppInfo = { onOpen(AppRoute.AppInfo) },
            modifier = modifier,
            onOpenSurvey = { onOpen(AppRoute.Survey) },
            // 실제 디버깅 도구는 디버그 빌드에만 실린다. 릴리스의 빈 모듈은 installed 가 늘 false 라 줄이 없다.
            onOpenDeveloperTools = if (DebugTools.installed) DebugTools::open else null,
        )

        AppRoute.Survey -> SurveyRoute(viewModel = viewModel { graph.surveyViewModel }, onDone = onBack, modifier = modifier)

        is AppRoute.ReportDetail -> SessionReportRoute(
            viewModel = viewModel(key = route.toString()) { graph.sessionReportViewModelFactory.create(route.sessionId) },
            onOpenAnswer = { onOpen(AppRoute.AnswerReport(it)) },
            modifier = modifier,
        )

        is AppRoute.AnswerReport -> AnswerReportRoute(
            viewModel = viewModel(key = route.toString()) { graph.answerReportViewModelFactory.create(route.answerId) },
            modifier = modifier,
        )

        // 시험이 끝나면 시험 화면을 백스택에서 빼고 그 회차 리포트를 연다. 뒤로가기로 끝난 시험에 돌아가지 않는다.
        is AppRoute.Exam -> ExamRoute(
            viewModel = viewModel(key = route.toString()) { graph.examViewModelFactory.create(route.sessionId) },
            onExit = onExit,
            onFinish = {
                onExit()
                onOpen(AppRoute.ReportDetail(route.sessionId))
            },
            modifier = modifier,
        )

        is AppRoute.Practice -> PracticeRoute(
            viewModel = viewModel(key = route.toString()) { graph.practiceViewModelFactory.create(route.answerId) },
            onExit = onBack,
            onFeedback = {
                onBack()
                onOpen(AppRoute.AnswerReport(it))
            },
            modifier = modifier,
        )

        is AppRoute.Coach -> LiveCoachRoute(
            viewModel = viewModel(key = route.toString()) { graph.liveCoachViewModelFactory.create(route.sessionId, route.questionId, route.question, route.targetGrade) },
            onExit = onBack,
            modifier = modifier,
        )

        is AppRoute.KeyEntry -> {
            val viewModel = viewModel(key = route.toString()) {
                graph.keyEntryViewModelFactory.create(KeyProvider.entries.firstOrNull { it.id == route.provider })
            }
            BlockBackWhileChecking(viewModel, onBlockBack)
            KeyEntryRoute(viewModel = viewModel, onDone = onBack, onOpenFreeTierNotice = { onOpen(AppRoute.FreeTierNotice) }, modifier = modifier)
        }

        AppRoute.FreeTierNotice -> ConsentRoute(viewModel = viewModel { graph.consentViewModel }, onDone = onBack, modifier = modifier)

        AppRoute.FlagExport -> FlagExportRoute(viewModel = viewModel { graph.flagExportViewModel }, modifier = modifier)

        AppRoute.AppInfo -> AppInfoRoute(modifier = modifier)

        // 첫 실행은 제공사를 정하지 않는다(null). 제공사가 하나뿐이면 그 제공사가 골라져 있다. 키를 저장하면 동의 여부를 다시 읽어 동의 화면이나 홈으로 간다.
        AppRoute.StartKeyEntry -> {
            val viewModel = viewModel(key = route.toString()) { graph.keyEntryViewModelFactory.create(null) }
            BlockBackWhileChecking(viewModel, onBlockBack)
            KeyEntryRoute(viewModel = viewModel, onDone = restart, onOpenFreeTierNotice = { onOpen(AppRoute.FreeTierNotice) }, modifier = modifier)
        }

        AppRoute.StartConsent -> ConsentRoute(viewModel = viewModel { graph.consentViewModel }, onDone = restart, modifier = modifier)
    }
}

/** 키를 확인하는 동안 뒤로 나가지 못하게 한다. 나가면 확인하던 화면이 사라져 키가 저장됐는지 사용자가 알 수 없다. */
@Composable
private fun BlockBackWhileChecking(viewModel: KeyEntryViewModel, onBlockBack: (Boolean) -> Unit) {
    val checking = viewModel.uiState.collectAsStateWithLifecycle().value.checking
    val currentOnBlockBack by rememberUpdatedState(onBlockBack)
    LaunchedEffect(checking) { currentOnBlockBack(checking) }
}

@OptIn(ExperimentalUuidApi::class)
private fun newId(): String = Uuid.random().toString()
