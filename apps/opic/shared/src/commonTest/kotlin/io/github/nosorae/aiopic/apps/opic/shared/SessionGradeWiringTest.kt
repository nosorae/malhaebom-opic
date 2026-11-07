package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.apps.opic.shared.data.StoreExamRepository
import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.aigemini.GeminiModels
import io.github.nosorae.aiopic.engine.answer.model.Answer
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.packs.opicen.OpicEn
import io.github.nosorae.aiopic.ui.featurereport.range
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 회차 등급(F5.3)이 앱에서 이어지는지 본다. 그래프를 메모리 플랫폼 인자와 저장한 Gemini 응답으로 만들고,
 * 15문항 답변을 시험 저장소로 분석 작업 표에 넣은 뒤 작업을 비우면 회차 리포트(F7.2)와 기록(F7.3)에 같은 등급이 보여야 한다.
 * 호스트 JVM 테스트는 시험 팩의 프롬프트 파일을 못 읽어서 분석 작업 표는 그래프와 같은 조립 함수에 요청만 바꿔 넣는다([testAnalysisJobs]).
 */
class SessionGradeWiringTest {
    private val db = inMemoryDatabase()
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun close() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `F5_3 15문항 답변을 분석 작업으로 흘리면 회차 리포트와 기록에 같은 등급 범위가 보인다`() = runTest(dispatcher) {
        val sent = mutableListOf<String>()
        val client = GeminiClient(
            HttpClient(
                mockEngine { request ->
                    val grading = "brokeAt" in request.body.toByteArray().decodeToString()
                    sent += if (grading) "grade" else "answer"
                    respond(if (grading) GRADE_RESPONSE else ANSWER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )
        val settings = AppSettings.inMemory().apply { give() }
        val keyVault = FakeKeyVault().apply { put(GeminiModels.PROVIDER, "test-key") }
        val queue = FakeWorkQueue()
        val graph = createAppGraph(settings, keyVault, db, "recordings", FakeRecorder, FakeTts, queue, FakeDuplexAudio, client)
        val store = OpicStore(db)
        val jobs = testAnalysisJobs(store, queue, settings, client, keyVault)

        val repository = StoreExamRepository(store, jobs, OpicEn.examSpec, { emptyList() }, "recordings", settings, OpicEn.MAX_OUTSIDE_TOPICS, this) { _, _, _ -> null }
        val ids = (1..15).map { "a$it" }
        repository.save(ExamSession("s1", answerIds = ids))
        ids.forEach { repository.save(Answer(it, Question("q-$it", "Tell me.", QuestionType("description"), "home"), "s1", chunkPaths = emptyList())) }
        repository.save(store.session("s1").first()!!.copy(status = ExamStatus.Finished))

        assertNull(jobs.drain())

        assertEquals(List(15) { "answer" } + "grade", sent)
        val report = graph.sessionReportViewModelFactory.create("s1").uiState.first { it.report?.session?.grade != null }.report!!
        assertEquals("IM2 ~ IH", report.session.grade!!.range)
        val row = graph.historyViewModel.uiState.first { !it.loading }.sessions.single()
        assertEquals(report.session.grade, row.session.grade)
    }
}

private fun geminiText(json: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(json)}}]},"finishReason":"STOP"}]}"""

private val ANSWER_RESPONSE = geminiText(
    """{"transcript":"I live in an apartment.","corrections":[],"upgrades":[],"tenseConsistent":true,"sentenceCount":1,"wordsPerMinute":120.0,"pauses":[],"pronunciation":"Clear.","score":70}""",
)

private val GRADE_RESPONSE = geminiText(
    """{"checks":[{"id":"C1","met":true}],"low":"IM2","high":"IH","reasons":["C1 met."],"brokeAt":[15]}""",
)
