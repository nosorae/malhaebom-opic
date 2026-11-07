package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.answer.analysis.AnalysisJobs
import io.github.nosorae.aiopic.engine.answer.analysis.AnalysisRequest
import io.github.nosorae.aiopic.engine.answer.analysis.GradeRequest
import io.github.nosorae.aiopic.engine.answer.port.Prompt
import io.github.nosorae.aiopic.engine.answer.port.Recording
import io.github.nosorae.aiopic.engine.answer.store.OpicStore
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.libs.duplexaudio.api.MicChunk
import io.github.nosorae.aiopic.libs.keyvault.api.KeyVault
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.llmgemini.api.LlmResult
import io.github.nosorae.aiopic.libs.recorder.api.Recorder
import io.github.nosorae.aiopic.libs.recorder.api.RecordingFailure
import io.github.nosorae.aiopic.libs.recorder.api.StoppedRecording
import io.github.nosorae.aiopic.libs.tts.api.Tts
import io.github.nosorae.aiopic.libs.workqueue.api.WorkQueue
import io.github.nosorae.aiopic.packs.opicen.OpicEn
import io.github.nosorae.aiopic.ui.featureexam.ExamIntent
import io.github.nosorae.aiopic.ui.featureexam.ExamViewModel
import io.github.nosorae.aiopic.ui.featureonboarding.DomainError
import io.github.nosorae.aiopic.ui.featureonboarding.DomainResult
import io.github.nosorae.aiopic.ui.featureonboarding.consent.ConsentIntent
import io.github.nosorae.aiopic.ui.featureonboarding.key.KeyProvider
import io.github.nosorae.aiopic.ui.featureonboarding.start.StartDestination
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration

/** 서베이를 낼 수 있는 주제 12개. 묶음마다 최소(2, 1, 1, 1)를 넘게 앞에서부터 고른다. */
val testSurveyTopics: List<String> = OpicEn.survey.groups.zip(listOf(5, 3, 3, 1)).flatMap { (group, count) -> group.topics.take(count).map { it.id } }

/** Part 1~3 은 하위 질문이 가장 적은 길(일한 적 없음, 학생 아님, 수업 5년 넘음, 혼자)로 답하고 [topics] 를 고른 뒤 서베이를 낸다. */
fun ExamViewModel.submitSurvey(topics: List<String> = testSurveyTopics) {
    listOf("none", "no", "over-5-years", "alone").forEach { onIntent(ExamIntent.ChooseSurveyOption(it)) }
    topics.filter { it !in uiState.value.survey?.topics.orEmpty() }.forEach { onIntent(ExamIntent.ToggleTopic(it)) }
    onIntent(ExamIntent.SubmitSurvey)
}

/** 기기 대신 메모리에 두는 플랫폼 인자로 그래프를 만든다. 앱 틀 테스트도 이것을 쓴다. */
fun testAppGraph(settings: AppSettings = AppSettings.inMemory(), keyVault: KeyVault = FakeKeyVault()): AppGraph = createAppGraph(
    settings = settings,
    keyVault = keyVault,
    database = inMemoryDatabase(),
    recordingsRoot = "recordings",
    recorder = FakeRecorder,
    tts = FakeTts,
    workQueue = FakeWorkQueue(),
    duplexAudio = FakeDuplexAudio,
)

/**
 * 그래프와 같은 조립 함수로 만든 분석 작업 표. 호스트 JVM 테스트는 시험 팩의 프롬프트 파일을 못 읽어서 요청만 바꿔 넣는다.
 * 기본 클라이언트는 늘 503 이라 실제로 보내는 테스트는 [client] 를 넣는다.
 */
internal fun testAnalysisJobs(
    store: OpicStore,
    queue: WorkQueue,
    settings: AppSettings,
    client: GeminiClient = GeminiClient(HttpClient(mockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) })),
    keyVault: KeyVault = FakeKeyVault(),
    clock: Clock = Clock.System,
): AnalysisJobs {
    val silence = Recording(ByteArray(0), "audio/mp4", emptyList())
    return analysisJobs(
        store = store,
        queue = queue,
        client = client,
        keyVault = keyVault,
        settings = settings,
        answerRequest = { _, model -> AnalysisRequest(model, Prompt("answer-v1", "answer", "{\"transcript\":{}}"), silence) },
        gradeRequest = { answers, model -> GradeRequest(model, Prompt("grade-v1", "grade", "{\"brokeAt\":{}}"), OpicEn.gradeScale, answers.map { silence }) },
        clock = clock,
    )
}

/**
 * 부른 스레드에서 바로 답하는 가짜 Gemini. 다른 스레드에서 답하면 그동안 테스트 시계가 넘어가 분석 작업의 시간 제한(2분)에 걸린다.
 */
fun mockEngine(handler: MockRequestHandler) = MockEngine(
    MockEngineConfig().apply {
        dispatcher = Dispatchers.Unconfined
        addHandler(handler)
    },
)

class FakeKeyVault : KeyVault {
    private val keys = mutableMapOf<String, String>()

    override suspend fun get(provider: String): String? = keys[provider]

    override suspend fun put(provider: String, key: String) {
        keys[provider] = key
    }

    override suspend fun delete(provider: String) {
        keys.remove(provider)
    }
}

object FakeRecorder : Recorder {
    override suspend fun start(directory: String): RecordingFailure? = null

    override suspend fun stop() = StoppedRecording(emptyList())

    override suspend fun chunks(directory: String) = emptyList<String>()

    override suspend fun merge(chunks: List<String>, output: String) = emptyList<Duration>()
}

/** 코치 오디오. 마이크는 아무것도 내지 않고 재생은 버린다. */
object FakeDuplexAudio : DuplexAudio {
    override val mic: Flow<MicChunk> = emptyFlow()
    override val micLevel: StateFlow<Float> = MutableStateFlow(0f)
    override val playbackLevel: StateFlow<Float> = MutableStateFlow(0f)

    override fun play(pcm: ByteArray) = Unit

    override fun flush() = Unit
}

object FakeTts : Tts {
    override suspend fun speak(text: String, audio: ByteArray?) = true

    override fun hasHighQualityVoice() = true

    override fun stop() = Unit
}

class AppGraphTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `그래프 하나로 모든 화면의 ViewModel 과 분석 처리기를 만든다`() {
        val graph = testAppGraph()

        graph.homeViewModel
        graph.settingsViewModel
        graph.consentViewModel
        graph.startViewModel
        graph.historyViewModel
        graph.flagExportViewModel
        graph.keyEntryViewModelFactory.create(KeyProvider.Gemini)
        graph.examViewModelFactory.create("session")
        graph.practiceViewModelFactory.create("answer")
        graph.sessionReportViewModelFactory.create("session")
        graph.answerReportViewModelFactory.create("answer")
        graph.analysisJobs
    }

    @Test
    fun `F1_3 동의는 그래프를 다시 만들어도 남는다`() = runTest(dispatcher) {
        val disk = mutableMapOf<String, String>()
        val keyVault = FakeKeyVault().apply { put(KeyProvider.Gemini.id, "test-key") }
        fun reopen() = testAppGraph(AppSettings(read = { disk[it] }, write = { key, value -> disk[key] = value }), keyVault)

        val before = reopen().startViewModel
        advanceUntilIdle()
        assertEquals(StartDestination.Consent, before.uiState.value.destination)

        reopen().consentViewModel.onIntent(ConsentIntent.GiveConsent)
        advanceUntilIdle()

        val after = reopen().startViewModel
        advanceUntilIdle()
        assertEquals(StartDestination.Home, after.uiState.value.destination)
    }

    @Test
    fun `F1_1 Gemini 가 키를 거절하면 틀린 키이고 연결이나 한도 문제면 잠시 못 씀이다`() {
        assertEquals(DomainResult.Success(Unit), geminiKeyCheck(LlmResult.Success("ok")))
        assertEquals(DomainResult.Failure(DomainError.InvalidKey), geminiKeyCheck(LlmResult.InvalidKey))
        assertEquals(DomainResult.Failure(DomainError.Unavailable), geminiKeyCheck(LlmResult.Retryable(429)))
        assertEquals(DomainResult.Failure(DomainError.Unavailable), geminiKeyCheck(LlmResult.Retryable(null)))
    }
}
