package io.github.nosorae.aiopic.apps.opic.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.engine.answer.model.AnswerAnalysis
import io.github.nosorae.aiopic.engine.answer.model.JobState
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.engine.session.model.ExamStep
import io.github.nosorae.aiopic.engine.session.model.Recalibration
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.recorder.api.Recorder
import io.github.nosorae.aiopic.libs.recorder.api.RecordingFailure
import io.github.nosorae.aiopic.libs.recorder.api.StoppedRecording
import io.github.nosorae.aiopic.libs.tts.api.Tts
import io.github.nosorae.aiopic.ui.featureexam.ExamIntent
import io.github.nosorae.aiopic.ui.featureexam.ExamViewModel
import io.github.nosorae.aiopic.ui.featureexam.QuestionPhase
import io.github.nosorae.aiopic.ui.featurehistory.HistoryViewModel
import io.github.nosorae.aiopic.ui.featureonboarding.consent.ConsentIntent
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyEntryIntent
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyProvider
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartDestination
import io.github.nosorae.aiopic.ui.featurepractice.PracticeDestination
import io.github.nosorae.aiopic.ui.featurepractice.PracticeIntent
import io.github.nosorae.aiopic.ui.featurepractice.PracticePhase
import io.github.nosorae.aiopic.ui.featurepractice.PracticeViewModel
import io.github.nosorae.aiopic.ui.featurereport.AnswerReportViewModel
import io.github.nosorae.aiopic.ui.featurereport.SessionReportViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 모의고사 한 회를 iOS 에서 앱 그래프로 끝까지 돈다(완료 기준). 화면 대신 화면이 쓰는 ViewModel 에 같은 의도를 보낸다.
 * DB 는 실제 파일이고 Gemini 는 저장한 응답이다. 시뮬레이터 테스트에는 마이크와 음성이 없어 녹음기와 기기 음성은 가짜다.
 *
 * 강제 종료는 ViewModel 을 모두 치우고 DB 를 닫은 뒤 같은 DB 파일, 같은 설정, 같은 녹음 폴더로 그래프를 새로 만드는 것으로 본다.
 * 시계는 테스트가 손으로 넘겨서 녹음 10초 규칙(F2.4)을 기다리지 않는다.
 */
@OptIn(ExperimentalForeignApi::class)
class ExamE2eIosTest {
    private val dispatcher = StandardTestDispatcher()
    private val dbPath = "${NSTemporaryDirectory()}e2e-${kotlin.random.Random.nextLong()}.db"

    // 앱을 다시 켜도 남는 것: 설정, 키, 녹음 폴더, 분석 대기열
    private val prefs = mutableMapOf<String, String>()
    private val keyVault = FakeKeyVault()
    private val recordedDirectories = mutableSetOf<String>()
    private val queue = FakeWorkQueue()
    private var now = Duration.ZERO
    private val launched = mutableListOf<App>()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        NSFileManager.defaultManager.removeItemAtPath(dbPath, null)
    }

    private class App(val graph: AppGraph, val database: OpicDatabase, val viewModels: ViewModelStore = ViewModelStore()) {
        inline fun <reified T : ViewModel> viewModel(key: String = T::class.qualifiedName!!, crossinline create: AppGraph.() -> T): T = ViewModelProvider.create(viewModels, viewModelFactory { initializer { graph.create() } })[key, T::class]

        /** 프로세스가 죽은 것처럼 화면의 작업을 모두 멈추고 DB 를 닫는다. 두 번 불러도 된다. */
        private var alive = true

        fun kill() {
            if (!alive) return
            alive = false
            viewModels.clear()
            database.close()
        }
    }

    private fun launch(): App {
        // 쿼리를 테스트 시계 위에서 돌린다. 다른 스레드에서 도는 동안 테스트 시계가 분석 시간 제한(2분)을 넘겨 버리지 않게 한다.
        val database = Room.databaseBuilder<OpicDatabase>(name = dbPath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(dispatcher).build()
        val graph = createAppGraph(
            settings = AppSettings(read = { prefs[it] }, write = { key, value -> prefs[key] = value }),
            keyVault = keyVault,
            database = database,
            recordingsRoot = "recordings",
            recorder = DiskRecorder(recordedDirectories),
            tts = SilentTts,
            workQueue = queue,
            duplexAudio = FakeDuplexAudio,
            gemini = GeminiClient(HttpClient(mockEngine { respond(if ("brokeAt" in it.body.toByteArray().decodeToString()) GRADE_RESPONSE else ANSWER_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
            timer = { now },
        )
        return App(graph, database).also { launched += it }
    }

    @Test
    fun `완료 기준 첫 실행에서 모의고사 한 회를 보다 앱이 죽어도 이어 보고 리포트와 기록과 한 문항 연습까지 간다`() = runTest(dispatcher, timeout = 2.minutes) {
        // 실패해도 ViewModel 의 1초 시계를 멈춰야 runTest 가 남은 작업을 비우고 끝난다.
        try {
            // 첫 실행(F1.0): 키 넣기, 동의
            val first = launch()
            assertEquals(StartDestination.KeyEntry, first.graph.decideStartUseCase())
            val key = first.viewModel { keyEntryViewModelFactory.create(null) }
            listOf(KeyEntryIntent.SelectProvider(KeyProvider.Gemini), KeyEntryIntent.ChangeKey("test-key"), KeyEntryIntent.SubmitKey).forEach(key::onIntent)
            key.uiState.first { it.navigation != null }
            val consent = first.viewModel { consentViewModel }
            consent.onIntent(ConsentIntent.GiveConsent)
            consent.uiState.first { it.navigation != null }
            assertEquals(StartDestination.Home, first.graph.decideStartUseCase())

            // 서베이 12개, 1단계(12문항), 장비 점검, 1번 문항, 2번 문항 녹음 중에 죽는다
            val exam = first.viewModel { examViewModelFactory.create("s1") }
            exam.uiState.first { it.step is ExamStep.Survey && it.survey != null }
            exam.submitSurvey()
            exam.uiState.first { it.step == ExamStep.LevelSelect }
            exam.onIntent(ExamIntent.SelectLevel(1))
            exam.onIntent(ExamIntent.SubmitLevel)
            exam.uiState.first { it.step == ExamStep.Check }
            exam.answer()
            exam.uiState.first { it.questionNumber == 1 }
            exam.answer()
            exam.uiState.first { it.questionNumber == 2 && it.phase == QuestionPhase.Ready }
            exam.onIntent(ExamIntent.PlayQuestion)
            exam.uiState.first { it.phase == QuestionPhase.Recording }
            now += 3.seconds
            first.kill()

            // 다시 켜면 홈이 같은 회차를 이어 보고(F3), 2번 문항에서 이어 녹음하며 다시 듣기를 쓴 것으로 센다(F3.3)
            val second = launch()
            assertEquals(StartDestination.Home, second.graph.decideStartUseCase())
            assertEquals("s1", second.graph.examSessionSource.sessions().first().last { it.status == ExamStatus.InProgress }.id)
            val resumed = second.viewModel { examViewModelFactory.create("s1") }
            assertEquals(12, resumed.uiState.first { it.step != null }.questionCount)
            assertEquals(2, resumed.uiState.value.questionNumber)
            resumed.onIntent(ExamIntent.PlayQuestion)
            assertTrue(resumed.uiState.first { it.phase == QuestionPhase.Recording && it.voiceLevel > 0f }.replayUsed)
            now += 11.seconds
            resumed.onIntent(ExamIntent.SubmitAnswer)
            while (resumed.uiState.first { it.navigation != null || it.phase == QuestionPhase.Ready }.navigation == null) {
                if (resumed.uiState.value.step == ExamStep.Recalibrate) {
                    resumed.onIntent(ExamIntent.SelectRecalibration(Recalibration.Same))
                    resumed.onIntent(ExamIntent.SubmitRecalibration)
                    resumed.uiState.first { it.step is ExamStep.Ask }
                }
                resumed.answer()
            }

            // 분석 작업 표(F5.1)를 비우면 리포트(F7.2)에 답변 12개가 저장한 응답의 점수로 나온다
            assertEquals(12, second.graph.opicStore.jobs().first().size)
            // 마지막 답변 분석 뒤 회차 등급 작업(F5.3)이 붙어 같은 비우기에서 끝난다.
            assertNull(second.graph.analysisJobs.drain())
            assertEquals(List(13) { JobState.Succeeded }, second.graph.opicStore.jobs().first().map { it.state })
            val report = second.viewModel { sessionReportViewModelFactory.create("s1") }.uiState.first { it.report != null }.report!!
            assertEquals(ExamStatus.Finished, report.session.status)
            assertEquals("IM2", report.session.grade?.low)
            assertEquals(List(12) { FAKE_SCORE }, report.answers.map { (it.analysis as AnswerAnalysis.Done).feedback.score })
            val answer = second.viewModel("answer-${report.answers.first().id}") { answerReportViewModelFactory.create(report.answers.first().id) }
            assertEquals(FAKE_TRANSCRIPT, (answer.uiState.first { it.answer != null }.answer!!.analysis as AnswerAnalysis.Done).feedback.transcript)

            // 기록(F7.3)
            assertEquals(listOf("s1"), second.viewModel<HistoryViewModel> { historyViewModel }.uiState.first { !it.loading }.sessions.map { it.session.id })

            // 한 문항 연습(F4): 답을 내면 그 답변 리포트로 가고 분석이 붙는다
            val practice = second.viewModel { practiceViewModelFactory.create("p1", null, null) }
            practice.uiState.first { it.canPlay }
            practice.onIntent(PracticeIntent.PlayQuestion)
            practice.uiState.first { it.phase == PracticePhase.Recording && it.voiceLevel > 0f }
            now += 11.seconds
            practice.onIntent(PracticeIntent.SubmitAnswer)
            assertEquals(PracticeDestination.Feedback("p1"), practice.uiState.first { it.navigation != null }.navigation)
            assertNull(second.graph.analysisJobs.drain())
            val feedback = second.viewModel("answer-p1") { answerReportViewModelFactory.create("p1") }
            assertEquals(FAKE_SCORE, (feedback.uiState.first { it.answer?.analysis is AnswerAnalysis.Done }.answer!!.analysis as AnswerAnalysis.Done).feedback.score)
        } finally {
            launched.forEach(App::kill)
        }
    }

    /** 질문을 듣고 녹음을 시작해 목소리가 들어간 뒤 10초를 넘겨 낸다. 다음 단계로 넘어가거나 시험이 끝날 때까지 기다린다. */
    private suspend fun ExamViewModel.answer() {
        onIntent(ExamIntent.PlayQuestion)
        uiState.first { it.phase == QuestionPhase.Recording && it.voiceLevel > 0f }
        now += 11.seconds
        onIntent(ExamIntent.SubmitAnswer)
        uiState.first { it.phase != QuestionPhase.Recording || it.navigation != null }
    }
}

/**
 * 녹음한 폴더를 기기 디스크처럼 그래프 밖에 남긴다. 조각 파일은 만들지 않아서 분석에는 빈 녹음이 간다.
 * 음량은 보통 말소리 크기를 한 번 내서 답변이 무음으로 걸러지지 않게 한다(#241).
 */
private class DiskRecorder(private val directories: MutableSet<String>) : Recorder {
    override val levels = flowOf(0.5f)

    override suspend fun start(directory: String): RecordingFailure? = directories.add(directory).let { null }

    override suspend fun stop() = StoppedRecording(emptyList())

    override suspend fun chunks(directory: String) = if (directory in directories) listOf("$directory/00000-r.m4a") else emptyList()

    override suspend fun merge(chunks: List<String>, output: String) = emptyList<Duration>()
}

private object SilentTts : Tts {
    override suspend fun speak(text: String, audio: ByteArray?) = true

    override fun hasHighQualityVoice() = true

    override fun stop() = Unit
}

private const val FAKE_TRANSCRIPT = "I live in Seoul and I go jogging every morning."

private const val FAKE_SCORE = 80

private const val GRADE_RESPONSE = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"checks\":[{\"id\":\"C1\",\"met\":true}],\"low\":\"IM2\",\"high\":\"IH\",\"reasons\":[\"C1 met.\"],\"brokeAt\":[]}"}]},"finishReason":"STOP"}]}"""

/** 키 확인과 답변 분석 요청에 모두 같은 저장 응답을 돌려준다. Android 시나리오 테스트와 같은 응답이다. */
private const val ANSWER_RESPONSE = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"transcript\":\"$FAKE_TRANSCRIPT\",\"corrections\":[],\"upgrades\":[],\"tenseConsistent\":true,\"sentenceCount\":2,\"wordsPerMinute\":120.0,\"pauses\":[],\"pronunciation\":\"Clear.\",\"score\":$FAKE_SCORE}"}]},"finishReason":"STOP"}]}"""
