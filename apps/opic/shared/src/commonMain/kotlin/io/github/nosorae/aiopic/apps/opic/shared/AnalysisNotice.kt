package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.answer.model.AnalysisJob
import io.github.nosorae.aiopic.engine.answer.model.JobKind
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * 앱 밖에서 보이는 분석 진행(#229). Android 는 포그라운드 서비스 알림, iOS 26 이상은 시스템 진행 표시에 쓴다.
 * 상태는 분석 작업 표에서만 읽는다(ADR 0032).
 */
data class AnalysisProgress(val done: Int, val total: Int)

/** 진행 알림에 그대로 띄울 값. 문구는 Compose 리소스에서 읽어 둔다. 기다리면 무엇이 나오는지와 몇 개 남았는지를 함께 쓴다. */
data class ProgressNotice(val done: Int, val total: Int, val title: String, val text: String)

suspend fun AnalysisProgress.notice(): ProgressNotice = ProgressNotice(done, total, getString(Res.string.analysis_progress_title), getString(Res.string.analysis_progress_text, total, done))

/**
 * 앱이 화면에 없을 때 분석이 끝나면 띄우는 알림. 누르면 [route] 로 가고, null 이면 앱 첫 화면을 연다.
 * [cards] 가 있으면 연습 정리 끝 알림이고 그 연습에서 만든 카드 수다(F17.5).
 */
data class AnalysisOutcome(val route: AppRoute?, val failed: Boolean, val cards: Int? = null) {
    suspend fun title(): String = getString(
        when {
            cards != null -> Res.string.cards_ready_title
            failed -> Res.string.analysis_failed_title
            else -> Res.string.analysis_ready_title
        },
    )

    suspend fun text(): String = when {
        cards == null -> getString(if (failed) Res.string.analysis_failed_text else Res.string.analysis_ready_text)
        cards == 0 -> getString(Res.string.cards_none_text)
        else -> getString(Res.string.cards_ready_text, cards)
    }
}

/**
 * [since] 에 비우기를 시작한 뒤의 진행. 그 뒤 끝난 작업과 아직 남은 작업을 센다. 남은 작업이 없으면 null 이다.
 * ponytail: 비우기 한 번을 한 묶음으로 센다. 다시 보내기로 비우기가 나뉘면 뒤 비우기는 남은 것만 센다.
 */
fun analysisProgress(jobs: List<AnalysisJob>, since: Instant): AnalysisProgress? {
    val left = jobs.count { it.state.active }
    if (left == 0) return null
    val done = jobs.count { !it.state.active && it.updatedAt >= since }
    return AnalysisProgress(done, done + left)
}

/**
 * [since] 에 시작한 비우기가 끝난 뒤 알릴 결과. 남은 작업이 있거나 그 뒤 끝난 작업이 없으면 null 이다.
 * 실패가 있으면 실패한 작업의, 없으면 마지막에 끝난 작업의 리포트로 보낸다. 모의고사 답변은 회차 리포트로 간다.
 * [sessionOf] 는 답변 id 로 회차 id 를 찾는다. 연습 답변이면 null 이다. [cardsOf] 는 연습 id 로 만든 카드 수를 찾는다.
 * 연습 정리는 끝났을 때만 알린다(F17.5). 실패한 정리는 앱을 켤 때 다시 넣는다.
 */
// ponytail: 연습 정리 끝 알림은 경로 없이 앱 첫 화면을 연다. 지금 연습 탭은 시험 연습 탭이라 정리와 상관없다. 연습 정리 화면(speaking-ui)이 생기면 그 화면 경로로 바꾼다.
fun analysisOutcome(jobs: List<AnalysisJob>, since: Instant, cardsOf: (String) -> Int? = { null }, sessionOf: (String) -> String?): AnalysisOutcome? {
    if (jobs.any { it.state.active }) return null
    val finished = jobs.filter { it.updatedAt >= since && !(it.kind == JobKind.Cards && it.state == JobState.Failed) }
    val job = finished.firstOrNull { it.state == JobState.Failed } ?: finished.maxByOrNull { it.updatedAt } ?: return null
    val route = when (job.kind) {
        JobKind.Grade -> AppRoute.ReportDetail(job.targetId)
        JobKind.Answer -> sessionOf(job.targetId)?.let { AppRoute.ReportDetail(it) } ?: AppRoute.AnswerReport(job.targetId)
        JobKind.Cards -> return AnalysisOutcome(null, failed = false, cards = cardsOf(job.targetId) ?: 0)
    }
    return AnalysisOutcome(route, failed = job.state == JobState.Failed)
}

/** 지금부터 시작하는 비우기의 진행. 작업 표가 바뀔 때마다 새 값을 낸다. */
fun OpicStore.analysisProgress(clock: Clock): Flow<ProgressNotice?> {
    val since = clock.nowMillis()
    return jobs().map { analysisProgress(it, since) }.distinctUntilChanged().map { it?.notice() }
}

/** 기다리거나 보내는 분석 작업이 있는가. 첫 분석을 시작할 때 알림 권한을 묻는 데 쓴다. */
fun OpicStore.analyzing(): Flow<Boolean> = jobs().map { jobs -> jobs.any { it.state.active } }.distinctUntilChanged()

/**
 * 분석 작업 표를 [drain] 으로 한 번 비우고, 그 사이 끝난 작업이 있고 남은 작업이 없을 때 앱이 화면에 없으면([visible] 이 false) [notify] 로 결과를 알린다.
 * 플랫폼 대기열의 비우기 자리에 넣는다. [cardCount] 는 연습 정리 끝 알림에 실을 그 연습의 카드 수를 읽는다(F17.5).
 */
suspend fun OpicStore.drainAndNotify(clock: Clock, drain: suspend () -> Duration?, visible: () -> Boolean, cardCount: suspend (String) -> Int? = { null }, notify: suspend (AnalysisOutcome) -> Unit): Duration? {
    val since = clock.nowMillis()
    val next = drain()
    if (!visible()) {
        val jobs = jobs().first()
        val sessions = answers().first().associate { it.id to it.examSessionId }
        val cards = jobs.filter { it.kind == JobKind.Cards && it.updatedAt >= since }.associate { it.targetId to cardCount(it.targetId) }
        analysisOutcome(jobs, since, { cards[it] }, sessions::get)?.let { notify(it) }
    }
    return next
}

/** 플랫폼 대기열의 비우기. 그래프의 작업 표를 비우고 끝 알림에 연습 정리의 카드 수를 싣는다(F17.5). */
suspend fun AppGraph.drainAndNotify(visible: () -> Boolean, notify: suspend (AnalysisOutcome) -> Unit): Duration? = opicStore.drainAndNotify(clock, analysisJobs::drain, visible, speakingStore::cardCount, notify)

/** 작업 표는 시각을 밀리초로 저장한다. 시작 시각도 밀리초로 잘라야 같은 밀리초에 끝난 작업을 놓치지 않는다. */
private fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())

/** 알림에 실어 보낼 경로. 누르면 [routeFromJson] 으로 되읽는다. 경로가 없으면 null 이다. */
val AnalysisOutcome.routeJson: String? get() = route?.let { Json.encodeToString(AppRoute.serializer(), it) }

/** 알림에서 받은 경로. 앱 밖에서 온 값이라 읽지 못하면 null 이다. */
fun routeFromJson(json: String): AppRoute? = runCatching { Json.decodeFromString(AppRoute.serializer(), json) }.getOrNull()
