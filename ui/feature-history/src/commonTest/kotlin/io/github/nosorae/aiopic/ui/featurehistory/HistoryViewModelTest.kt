package io.github.nosorae.aiopic.ui.featurehistory

import io.github.nosorae.aiopic.engine.answer.model.AnalysisJob
import io.github.nosorae.aiopic.engine.answer.model.AnalyzedBy
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.model.AnswerAnalysis
import io.github.nosorae.aiopic.engine.answer.model.Correction
import io.github.nosorae.aiopic.engine.answer.model.Feedback
import io.github.nosorae.aiopic.engine.answer.model.JobKind
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.model.ModelInfo
import io.github.nosorae.aiopic.engine.answer.model.Pronunciation
import io.github.nosorae.aiopic.engine.answer.model.Stability
import io.github.nosorae.aiopic.engine.answer.port.ModelCatalog
import io.github.nosorae.aiopic.engine.answer.weakness.CorrectionFlag
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.engine.session.model.GradeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val modelA = ModelInfo("gemini", "model-a", "Model A", hearsAudio = true, stability = Stability.Stable)
    private val catalog = object : ModelCatalog {
        override val defaultModel = modelA
        override val models = listOf(modelA)
    }
    private val typeNames = mapOf(QuestionType("routine") to "습관", QuestionType("roleplay") to "롤플레이")

    private fun useCase(store: FakeHistoryStore) = ObserveHistoryUseCase(store, typeNames, catalog, GradeScale(listOf("NL", "IM", "AL")))

    private fun TestScope.subscribed(store: FakeHistoryStore) = HistoryViewModel(useCase(store), MarkWeaknessSeenUseCase(store)).also { viewModel ->
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun `F14_6 스크립트는 같은 질문이어도 하나씩 기록 맨 위에 올린다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished)))
        val scripts = listOf(HistoryScript("c2", "q1", "Tell me about a trip.", "AL"), HistoryScript("c1", "q1", "Tell me about a trip.", "IH"))
        store.scripts.value = scripts

        val viewModel = subscribed(store)
        advanceUntilIdle()

        assertEquals(scripts + viewModel.uiState.value.sessions, viewModel.uiState.value.entries)
    }

    @Test
    fun `F7_3 끝난 회차만 최근 것부터 기록에 올린다`() = runTest(dispatcher) {
        val sessions = listOf(
            ExamSession(id = "s1", status = ExamStatus.Finished),
            ExamSession(id = "s2", status = ExamStatus.InProgress),
            ExamSession(id = "s3", status = ExamStatus.TimeUp),
        )

        val viewModel = subscribed(FakeHistoryStore(sessions))
        advanceUntilIdle()

        assertEquals(listOf("s3", "s1"), viewModel.uiState.value.sessions.map { it.session.id })
        assertEquals(listOf(2, 1), viewModel.uiState.value.sessions.map { it.number })
    }

    @Test
    fun `F5_7 회차마다 분석한 모델을 모으고 섞였으면 표시한다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(
            sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished), ExamSession(id = "s2", status = ExamStatus.Finished)),
            answers = listOf(answer("a1", "s1", "model-a"), answer("a2", "s1", "model-b"), answer("a3", "s2", "model-a"), answer("a4", "s2", "model-a")),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()

        val (second, first) = viewModel.uiState.value.sessions
        assertTrue(first.mixedModels)
        assertEquals(listOf("Model A"), second.models)
        assertFalse(second.mixedModels)
    }

    @Test
    fun `F5_7 모델은 표시 이름으로 보이고 목록에 없는 모델만 id 로 보인다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(
            sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished)),
            answers = listOf(answer("a1", "s1", "model-a"), answer("a2", "s1", "retired-model")),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()

        assertEquals(listOf("Model A", "retired-model"), viewModel.uiState.value.sessions.single().models)
    }

    @Test
    fun `F7_3 한 문항 연습까지 모든 답변으로 문항 유형별 약점을 약한 것부터 낸다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(
            answers = listOf(answer("a1", "s1", score = 80, type = "routine"), answer("a2", null, score = 40, type = "roleplay")),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()

        assertEquals(listOf("롤플레이" to 40, "습관" to 80), viewModel.uiState.value.weaknesses.map { it.typeName to it.score })
    }

    @Test
    fun `F7_4 회차와 한 문항 연습 답변을 저장한 순서로 섞어 최근 것부터 보인다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(
            sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished), ExamSession(id = "s2", status = ExamStatus.Finished)),
            answers = listOf(answer("p1", null), answer("a1", "s1"), answer("a2", "s1"), answer("p2", null), answer("a3", "s2")),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()

        val ids = viewModel.uiState.value.entries.map {
            when (it) {
                is HistorySession -> it.session.id
                is HistoryPractice -> it.answerId
                is HistoryScript -> it.sessionId
            }
        }
        assertEquals(listOf("s2", "p2", "s1", "p1"), ids)
    }

    @Test
    fun `F7_4 연습 답변은 문항 유형 이름과 분석 상태와 모델을 가진다`() = runTest(dispatcher) {
        val failed = answer("p2", null, type = "roleplay").copy(analysis = AnswerAnalysis.Failed(AnalysisError.Retryable))
        val store = FakeHistoryStore(answers = listOf(answer("p1", null, score = 72, type = "routine"), failed))

        val viewModel = subscribed(store)
        advanceUntilIdle()

        val (second, first) = viewModel.uiState.value.entries.map { it as HistoryPractice }
        assertEquals("습관", first.typeName)
        assertEquals("Model A", first.model)
        assertEquals(72, (first.analysis as AnswerAnalysis.Done).feedback.score)
        assertEquals("롤플레이", second.typeName)
        assertEquals(null, second.model)
        assertTrue(second.analysis is AnswerAnalysis.Failed)
    }

    @Test
    fun `F7_4 분석을 기다리는 연습 답변은 분석 작업 표의 상태를 따라간다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(answers = listOf(answer("p1", null).copy(analysis = AnswerAnalysis.Pending)))
        store.jobs.value = listOf(
            AnalysisJob(JobKind.Grade, "p1", JobState.Failed, updatedAt = Instant.fromEpochSeconds(0)),
            AnalysisJob(JobKind.Answer, "p1", JobState.WaitingNetwork, updatedAt = Instant.fromEpochSeconds(0)),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()
        assertEquals(JobState.WaitingNetwork, (viewModel.uiState.value.entries.single() as HistoryPractice).job)

        store.jobs.value = listOf(AnalysisJob(JobKind.Answer, "p1", JobState.Running, updatedAt = Instant.fromEpochSeconds(1)))
        advanceUntilIdle()
        assertEquals(JobState.Running, (viewModel.uiState.value.entries.single() as HistoryPractice).job)
    }

    @Test
    fun `회차 답변이 없는 회차는 앞 회차 바로 뒤에 둔다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(
            sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished), ExamSession(id = "s2", status = ExamStatus.TimeUp)),
            answers = listOf(answer("a1", "s1"), answer("p1", null)),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.entries.size)
        assertTrue(viewModel.uiState.value.entries.first() is HistoryPractice)
        assertEquals(listOf("s2", "s1"), viewModel.uiState.value.sessions.map { it.session.id })
    }

    @Test
    fun `이름을 모르는 문항 유형은 id 로 보인다`() = runTest(dispatcher) {
        val viewModel = subscribed(FakeHistoryStore(answers = listOf(answer("a1", "s1", type = "new-type"))))
        advanceUntilIdle()

        assertEquals(listOf("new-type"), viewModel.uiState.value.weaknesses.map { it.typeName })
    }

    @Test
    fun `F8_2 틀림을 표시하면 그 답변이 약점 점수에서 빠진다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(answers = listOf(answer("a1", "s1", score = 40), answer("a2", "s1", score = 80)))
        val viewModel = subscribed(store)
        advanceUntilIdle()

        store.flags.value = setOf(CorrectionFlag("a1", 0))
        advanceUntilIdle()

        assertEquals(listOf("t" to 80), viewModel.uiState.value.weaknesses.map { it.typeName to it.score })
    }

    /** 기록을 한 번 열어 지금 약점 점수를 보고 떠난 저장소. */
    private suspend fun seenOnce(vararg answers: Answer): FakeHistoryStore = FakeHistoryStore(answers = answers.toList()).also { useCase(it)().first() }

    @Test
    fun `198 처음 보는 약점 점수는 축하하지 않는다`() = runTest(dispatcher) {
        val viewModel = subscribed(FakeHistoryStore(answers = listOf(answer("a1", "s1", score = 40))))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.weaknesses.single().rose)
    }

    /** 화면이 오른 약점 줄을 그려 축하를 시작한 경우. */
    private fun HistoryViewModel.celebrate() = uiState.value.weaknesses.filter { it.rose }.forEach { onIntent(HistoryIntent.MarkWeaknessSeen(it)) }

    @Test
    fun `198 지난번에 본 것보다 오른 약점 유형만 작은 축하를 하고 축하한 뒤 다시 열면 하지 않는다`() = runTest(dispatcher) {
        val store = seenOnce(answer("a1", "s1", score = 40), answer("b1", "s1", score = 60, type = "routine"))
        store.answers.value += listOf(answer("a2", "s2", score = 80), answer("b2", "s2", score = 40, type = "routine"))

        val opened = subscribed(store)
        advanceUntilIdle()
        assertEquals(listOf("습관" to false, "t" to true), opened.uiState.value.weaknesses.map { it.typeName to it.rose })

        opened.celebrate()
        advanceUntilIdle()
        val again = subscribed(store)
        advanceUntilIdle()

        assertEquals(listOf(false, false), again.uiState.value.weaknesses.map { it.rose })
    }

    @Test
    fun `198 기록을 열기만 하고 약점 줄의 축하를 보지 않았으면 다음에 열 때 다시 축하한다`() = runTest(dispatcher) {
        val store = seenOnce(answer("a1", "s1", score = 40))
        store.answers.value += answer("a2", "s2", score = 80)

        subscribed(store)
        advanceUntilIdle()
        val again = subscribed(store)
        advanceUntilIdle()

        assertEquals(40, store.seen.value["weakness:t"])
        assertTrue(again.uiState.value.weaknesses.single().rose)
    }

    @Test
    fun `198 축하를 시작해 본 표시를 남겨도 보는 동안은 축하가 사라지지 않는다`() = runTest(dispatcher) {
        val store = seenOnce(answer("a1", "s1", score = 40))
        store.answers.value += answer("a2", "s2", score = 80)
        val viewModel = subscribed(store)
        advanceUntilIdle()

        viewModel.celebrate()
        advanceUntilIdle()

        assertEquals(60, store.seen.value["weakness:t"])
        assertTrue(viewModel.uiState.value.weaknesses.single().rose)
    }

    @Test
    fun `198 보는 중에 점수가 오르면 그때 축하한다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(answers = listOf(answer("a1", "s1", score = 40)))
        val viewModel = subscribed(store)
        advanceUntilIdle()

        store.answers.value += answer("a2", "s2", score = 80)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.weaknesses.single().rose)
    }

    @Test
    fun `198 오른 약점 축하는 앱을 다시 켜도 다시 터지지 않는다`() = runTest(dispatcher) {
        val before = seenOnce(answer("a1", "s1", score = 40))
        before.answers.value += answer("a2", "s2", score = 80)
        subscribed(before).also { advanceUntilIdle() }.celebrate()
        advanceUntilIdle()

        val restarted = subscribed(FakeHistoryStore(answers = before.answers.value, seen = before.seen.value))
        advanceUntilIdle()

        assertFalse(restarted.uiState.value.weaknesses.single().rose)
    }

    @Test
    fun `불러오기 전에는 빈 기록 대신 불러오는 중이다`() = runTest(dispatcher) {
        val viewModel = FakeHistoryStore().let { HistoryViewModel(useCase(it), MarkWeaknessSeenUseCase(it)) }

        assertTrue(viewModel.uiState.value.loading)
    }

    @Test
    fun `등급을 기다리는 회차는 답한 수와 분석이 끝난 수를 센다`() = runTest(dispatcher) {
        val store = FakeHistoryStore(
            sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished)),
            answers = listOf(answer("a1", "s1"), answer("a2", "s1").copy(analysis = AnswerAnalysis.Pending), answer("a3", "other")),
        )

        val viewModel = subscribed(store)
        advanceUntilIdle()

        val row = viewModel.uiState.value.sessions.single()
        assertEquals(2, row.answered)
        assertEquals(1, row.analyzed)
    }

    @Test
    fun `등급 눈금은 시험 팩의 등급 이름을 낮은 것부터 쓴다`() = runTest(dispatcher) {
        val viewModel = subscribed(FakeHistoryStore(sessions = listOf(ExamSession(id = "s1", status = ExamStatus.Finished))))
        advanceUntilIdle()

        assertEquals(listOf("NL", "IM", "AL"), viewModel.uiState.value.grades)
    }

    private fun answer(id: String, sessionId: String?, model: String = "model-a", score: Int = 70, type: String = "t") = Answer(
        id = id,
        question = Question("q", "text", QuestionType(type), "topic"),
        examSessionId = sessionId,
        chunkPaths = emptyList(),
        analysis = AnswerAnalysis.Done(
            AnalyzedBy("gemini", model, "v1"),
            Feedback("I go.", listOf(Correction("I go", "I went")), emptyList(), true, 1, 100.0, emptyList(), null, Pronunciation.NotEvaluated, score),
        ),
    )
}
