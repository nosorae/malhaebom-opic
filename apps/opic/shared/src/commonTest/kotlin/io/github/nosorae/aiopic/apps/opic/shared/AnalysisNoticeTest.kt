package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.answer.model.AnalysisJob
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.model.JobKind
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class AnalysisNoticeTest {
    private val start = Instant.fromEpochSeconds(1_000)
    private fun job(id: String, state: JobState, at: Long, kind: JobKind = JobKind.Answer) = AnalysisJob(kind, id, state, updatedAt = Instant.fromEpochSeconds(at))

    @Test
    fun `229 진행은 비우기를 시작한 뒤 끝난 작업 수와 남은 작업 수로 센다`() {
        val jobs = listOf(
            job("old", JobState.Succeeded, at = 10),
            job("a1", JobState.Succeeded, at = 1_001),
            job("a2", JobState.Failed, at = 1_002),
            job("a3", JobState.Running, at = 1_003),
            job("a4", JobState.Queued, at = 500),
        )

        assertEquals(AnalysisProgress(done = 2, total = 4), analysisProgress(jobs, since = start))
    }

    @Test
    fun `229 남은 작업이 없으면 진행을 내린다`() {
        assertNull(analysisProgress(listOf(job("a1", JobState.Succeeded, at = 1_001)), since = start))
    }

    @Test
    fun `229 모의고사 답변이 끝나면 회차 리포트로 연습 답변이면 답변 리포트로 보낸다`() {
        val sessionOf = mapOf("exam-answer" to "s1")::get

        assertEquals(
            AnalysisOutcome(AppRoute.ReportDetail("s1"), failed = false),
            analysisOutcome(listOf(job("exam-answer", JobState.Succeeded, at = 1_001)), start, sessionOf = sessionOf),
        )
        assertEquals(
            AnalysisOutcome(AppRoute.AnswerReport("practice"), failed = false),
            analysisOutcome(listOf(job("practice", JobState.Succeeded, at = 1_001)), start, sessionOf = sessionOf),
        )
        assertEquals(
            AnalysisOutcome(AppRoute.ReportDetail("s1"), failed = false),
            analysisOutcome(listOf(job("exam-answer", JobState.Succeeded, at = 1_001), job("s1", JobState.Succeeded, at = 1_002, JobKind.Grade)), start, sessionOf = sessionOf),
        )
    }

    @Test
    fun `229 하나라도 실패로 끝났으면 실패한 작업의 리포트로 실패를 알린다`() {
        val jobs = listOf(job("p1", JobState.Failed, at = 1_001), job("p2", JobState.Succeeded, at = 1_002))

        assertEquals(AnalysisOutcome(AppRoute.AnswerReport("p1"), failed = true), analysisOutcome(jobs, start) { null })
    }

    @Test
    fun `229 남은 작업이 있거나 이번에 끝난 작업이 없으면 알리지 않는다`() {
        assertNull(analysisOutcome(listOf(job("a1", JobState.Succeeded, at = 1_001), job("a2", JobState.RetryScheduled, at = 1_002)), start) { null })
        assertNull(analysisOutcome(listOf(job("a1", JobState.Succeeded, at = 10)), start) { null })
    }

    @Test
    fun `F17_5 연습 정리가 끝나면 만든 카드 수를 싣고 경로 없이 앱 첫 화면을 연다`() {
        val outcome = analysisOutcome(listOf(job("p1", JobState.Succeeded, at = 1_001, JobKind.Cards)), start, sessionOf = { null }, cardsOf = { if (it == "p1") 5 else null })

        assertEquals(AnalysisOutcome(null, failed = false, cards = 5), outcome)
        assertNull(outcome?.routeJson)
    }

    @Test
    fun `F17_5 연습 정리 실패는 끝 알림으로 알리지 않는다`() {
        assertNull(analysisOutcome(listOf(job("p1", JobState.Failed, at = 1_001, JobKind.Cards)), start) { null })
    }

    @Test
    fun `229 알림에 실은 경로를 되읽고 읽지 못하는 값은 버린다`() {
        val outcome = AnalysisOutcome(AppRoute.ReportDetail("s1"), failed = false)

        assertEquals(outcome.route, routeFromJson(outcome.routeJson!!))
        assertNull(routeFromJson("not a route"))
    }

    private val db = inMemoryDatabase()
    private val store = OpicStore(db)

    @AfterTest fun close() = db.close()

    /** 비우기를 시작한 시각. 밀리초 아래 자리가 있어야 시작 시각을 밀리초로 자르는지 드러난다. */
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochSeconds(2_000, nanosecondAdjustment = 999_999)
    }

    /**
     * 모의고사 회차 s1 의 답변 a1 을 분석하는 비우기. 끝나면 작업 표에 [finishedAt] 에 성공으로 남긴다.
     * 작업 표는 밀리초로 저장하므로 시작과 같은 밀리초에 끝난 작업은 시작 시각보다 앞선 값으로 읽힌다.
     */
    private suspend fun drainExamAnswer(visible: Boolean, finishedAt: Instant = clock.now()): List<AnalysisOutcome> {
        store.save(Answer("a1", Question("q1", "Tell me.", QuestionType("description"), "home"), "s1", chunkPaths = emptyList()))
        val notified = mutableListOf<AnalysisOutcome>()
        val next = store.drainAndNotify(
            clock = clock,
            drain = {
                store.save(AnalysisJob(JobKind.Answer, "a1", JobState.Succeeded, updatedAt = finishedAt))
                null
            },
            visible = { visible },
            notify = { notified += it },
        )
        assertNull(next)
        return notified
    }

    @Test
    fun `229 앱이 화면에 있으면 비우기가 끝나도 끝 알림을 띄우지 않는다`() = runTest {
        assertEquals(emptyList(), drainExamAnswer(visible = true))
    }

    @Test
    fun `229 앱이 화면에 없으면 비우기를 시작한 밀리초에 끝난 작업도 그 회차 리포트로 알린다`() = runTest {
        assertEquals(listOf(AnalysisOutcome(AppRoute.ReportDetail("s1"), failed = false)), drainExamAnswer(visible = false))
    }

    @Test
    fun `F17_5 앱이 화면에 없을 때 연습 정리가 끝나면 그 연습에서 만든 카드 수로 알린다`() = runTest {
        val notified = mutableListOf<AnalysisOutcome>()

        store.drainAndNotify(
            clock = clock,
            drain = {
                store.save(AnalysisJob(JobKind.Cards, "p1", JobState.Succeeded, updatedAt = clock.now()))
                null
            },
            visible = { false },
            cardCount = { 3 },
            notify = { notified += it },
        )

        assertEquals(listOf(AnalysisOutcome(null, failed = false, cards = 3)), notified)
    }

    @Test
    fun `229 비우기를 시작하기 전 밀리초에 끝난 작업은 이번 비우기의 결과로 알리지 않는다`() = runTest {
        assertEquals(emptyList(), drainExamAnswer(visible = false, finishedAt = Instant.fromEpochMilliseconds(1_999_999)))
    }
}
