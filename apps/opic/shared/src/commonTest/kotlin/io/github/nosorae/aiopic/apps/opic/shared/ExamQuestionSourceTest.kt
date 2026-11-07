package io.github.nosorae.aiopic.apps.opic.shared

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.aigemini.GeminiModels
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.session.model.ExamStep
import io.github.nosorae.aiopic.engine.session.model.Recalibration
import io.github.nosorae.aiopic.libs.llmgemini.GeminiClient
import io.github.nosorae.aiopic.libs.tts.api.Tts
import io.github.nosorae.aiopic.packs.opicen.OpicQuestionType
import io.github.nosorae.aiopic.packs.opicen.PromptFile
import io.github.nosorae.aiopic.ui.featureexam.ExamIntent
import io.github.nosorae.aiopic.ui.featureexam.ExamViewModel
import io.github.nosorae.aiopic.ui.featureexam.QuestionNotice
import io.github.nosorae.aiopic.ui.featureexam.QuestionPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 모의고사 출제(F6.1, F6.2)가 앱 그래프에서 이어지는지 본다. 그래프를 메모리 DB 와 가짜 Gemini 로 만들고 시험 화면의 ViewModel 에
 * 서베이, 수준, 점검을 보낸 뒤 기기 음성이 읽은 질문을 본다. 호스트 JVM 테스트는 시험 팩 파일을 못 읽어서 문항 풀과 출제 프롬프트만 바꿔 넣는다.
 */
class ExamQuestionSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val db = inMemoryDatabase()

    // 앱을 다시 켜도 남는 것: 설정, 키, DB
    private val prefs = mutableMapOf<String, String>()
    private val keyVault = FakeKeyVault()
    private val viewModels = mutableListOf<ViewModelStore>()
    private val spoken = mutableListOf<String>()
    private val prompts = mutableListOf<String>()
    private val voiceRequests = mutableListOf<String>()
    private val promptsLock = Mutex()
    private var now = Duration.ZERO

    private val topics = testSurveyTopics

    private val pool = OpicQuestionType.entries.flatMap { type ->
        (1..3).map { Question("pool-${type.name}-$it", "pool ${type.type.id} $it", type.type, if (type == OpicQuestionType.SelfIntro) "self" else topics[it]) }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** 시험 화면의 1초 시계가 돌고 있으면 runTest 가 끝나지 않아서, 끝날 때 ViewModel 을 모두 치운다. */
    private fun exam(body: suspend () -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            viewModels.forEach(ViewModelStore::clear)
        }
    }

    /** 출제 요청의 번호와 유형 줄마다 "generated 재조정 번호" 질문을 돌려주는 응답. */
    private fun generated(prompt: String): String {
        val recalibration = prompt.split(" | ")[2]
        val text = buildJsonObject {
            putJsonArray("questions") {
                Regex("""(?m)^(\d+)\. ([a-z-]+)$""").findAll(prompt).forEach { line ->
                    addJsonObject {
                        put("number", line.groupValues[1].toInt())
                        put("type", line.groupValues[2])
                        put("topic", topics[0])
                        put("text", "generated $recalibration ${line.groupValues[1]}")
                    }
                }
            }
        }.toString()
        return buildJsonObject {
            putJsonArray("candidates") { addJsonObject { putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } } } }
        }.toString()
    }

    /** 앱을 켜고 회차 s1 의 시험 화면을 연다. [gemini] 는 출제 요청에 돌려줄 상태다. */
    private suspend fun launch(gemini: HttpStatusCode, sessionId: String = "s1"): ExamViewModel {
        val settings = AppSettings(read = { prefs[it] }, write = { key, value -> prefs[key] = value }).apply { give() }
        keyVault.put(GeminiModels.PROVIDER, "test-key")
        val graph = createAppGraph(
            settings = settings,
            keyVault = keyVault,
            database = db,
            recordingsRoot = "recordings",
            recorder = FakeRecorder,
            tts = SpeechLog(spoken),
            workQueue = FakeWorkQueue(),
            duplexAudio = FakeDuplexAudio,
            gemini = GeminiClient(
                HttpClient(
                    MockEngine {
                        val prompt = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject["contents"]!!.jsonArray[0]
                            .jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                        // 질문 음성 요청은 따로 모으고 받지 못한 것으로 둔다. 화면은 기기 음성으로 읽는다.
                        if (QUESTION_VOICE_MODEL in it.url.toString()) {
                            promptsLock.withLock { voiceRequests += prompt }
                            return@MockEngine respond("{}", HttpStatusCode.TooManyRequests)
                        }
                        // 미리 받는 요청 셋이 동시에 들어와서 목록에 하나씩 넣는다.
                        promptsLock.withLock { prompts += prompt }
                        respond(if (gemini == HttpStatusCode.OK) generated(prompt) else "{}", gemini)
                    },
                ),
            ),
            timer = { now },
            pack = ExamPack(pool = { pool }, voice = { null }, questionsPrompt = { PromptFile("questions-test", "{{topics}} | {{level}} | {{recalibration}} | {{weakTypes}} | {{asked}} |\n{{order}}") }),
        )
        val store = ViewModelStore().also(viewModels::add)
        return ViewModelProvider.create(store, viewModelFactory { initializer { graph.examViewModelFactory.create(sessionId) } })[ExamViewModel::class]
    }

    /** 서베이, 수준 1, 점검 연습 문항을 지나 1번 문항의 질문을 듣는다. */
    private suspend fun ExamViewModel.firstQuestion(): String {
        uiState.first { it.step is ExamStep.Survey && it.survey != null }
        // 두 번째 회차부터는 지난 회차의 주제가 미리 골라져 있다(F2.2).
        submitSurvey(topics)
        uiState.first { it.step == ExamStep.LevelSelect }
        onIntent(ExamIntent.SelectLevel(1))
        onIntent(ExamIntent.SubmitLevel)
        uiState.first { it.step == ExamStep.Check }
        answer()
        uiState.first { it.questionNumber == 1 && it.phase == QuestionPhase.Ready }
        onIntent(ExamIntent.PlayQuestion)
        uiState.first { it.phase == QuestionPhase.Recording }
        return spoken.last()
    }

    private suspend fun ExamViewModel.answer() {
        onIntent(ExamIntent.PlayQuestion)
        uiState.first { it.phase == QuestionPhase.Recording }
        submit()
    }

    private suspend fun ExamViewModel.submit() {
        now += 11.seconds
        onIntent(ExamIntent.SubmitAnswer)
        uiState.first { it.phase != QuestionPhase.Recording }
    }

    /** 뒤에서 보낸 출제 요청이 가짜 Gemini 에 [count] 개 닿을 때까지 기다린다. 가짜 Gemini 는 테스트 디스패처 밖에서 돈다. */
    private suspend fun awaitPrompts(count: Int) {
        while (prompts.size < count) withContext(Dispatchers.Default) { delay(10) }
    }

    /** 지금 문항을 듣고 녹음이 시작되면 그 질문을 돌려준다. */
    private suspend fun ExamViewModel.play(): String {
        onIntent(ExamIntent.PlayQuestion)
        uiState.first { it.phase == QuestionPhase.Recording }
        return spoken.last()
    }

    /** [number] 번 문항 화면까지 답하며 간다. 재조정이 나오면 [choice] 를 고른다. */
    private suspend fun ExamViewModel.answerUntil(number: Int, choice: Recalibration = Recalibration.Same) {
        if (uiState.value.phase == QuestionPhase.Recording) submit()
        while (true) {
            val state = uiState.first { (it.step is ExamStep.Ask && it.phase == QuestionPhase.Ready) || it.step == ExamStep.Recalibrate }
            if (state.step == ExamStep.Recalibrate) {
                onIntent(ExamIntent.SelectRecalibration(choice))
                onIntent(ExamIntent.SubmitRecalibration)
                uiState.first { it.step != ExamStep.Recalibrate }
                continue
            }
            if (state.questionNumber == number) return
            answer()
        }
    }

    @Test
    fun `F6_1 점검과 1번은 Gemini 를 기다리지 않고 내장 문항이며 1번 Play 때 2번부터 재조정 앞까지를 요청한다`() = exam {
        val exam = launch(HttpStatusCode.OK)
        assertTrue(exam.firstQuestion().startsWith("pool self-intro"))

        assertTrue(spoken.first().startsWith("pool self-intro"), "점검의 연습 문항")
        awaitPrompts(1)
        val request = prompts.single()
        assertTrue(topics.all { it in request } && "| 1 |" in request && "| not chosen yet |" in request, request)
        assertTrue("2. description" in request && "7. past-experience" in request && "8. " !in request && "1. self-intro" !in request.substringAfter("|\n"), request)
        exam.answerUntil(2)
        assertEquals("generated not chosen yet 2", exam.play())
    }

    @Test
    fun `F6_1 5번을 마치면 재조정 선택지 셋을 미리 요청하고 고른 선택지의 질문이 8번부터 나온다`() = exam {
        Recalibration.entries.forEach { choice ->
            prompts.clear()
            val exam = launch(HttpStatusCode.OK, sessionId = "s-${choice.name}")
            exam.firstQuestion()
            exam.answerUntil(8, choice)
            awaitPrompts(4)

            assertEquals("generated ${choice.name.lowercase()} 8", exam.play())
            val tails = prompts.filter { "8. description" in it }
            assertEquals(setOf("easier", "same", "harder"), tails.map { it.split(" | ")[2] }.toSet(), "선택지마다 하나씩")
            assertTrue(tails.all { "2. description, ${topics[0]}" in it }, "앞에서 낸 질문을 함께 보낸다: ${tails.first()}")
            exam.answerUntil(9)
            assertEquals("generated ${choice.name.lowercase()} 9", exam.play())
            assertEquals(4, prompts.size, "받은 뒤에는 다시 요청하지 않는다")
            viewModels.forEach(ViewModelStore::clear)
        }
    }

    /** #161: 몫을 받으면 첫 질문 음성을, Play 하면 다음 질문 음성을 미리 받는다. 내장 문항은 앱에 있어 받지 않는다. */
    @Test
    fun `질문 음성을 출제와 함께 미리 받는다`() = exam {
        val exam = launch(HttpStatusCode.OK)
        exam.firstQuestion()
        awaitVoices("generated not chosen yet 2")
        exam.answerUntil(2)
        exam.play()
        awaitVoices("generated not chosen yet 2", "generated not chosen yet 3")
    }

    private suspend fun awaitVoices(vararg texts: String) {
        while (voiceRequests.toSet() != texts.toSet()) withContext(Dispatchers.Default) { delay(10) }
    }

    @Test
    fun `F6_2 Gemini 가 실패하면 내장 풀 질문이 나오고 화면에 알린다`() = exam {
        val exam = launch(HttpStatusCode.ServiceUnavailable)
        exam.firstQuestion()
        exam.answerUntil(2)

        assertTrue(exam.play().startsWith("pool description"))
        assertEquals(QuestionNotice.BuiltIn, exam.uiState.value.questionNotice)
    }

    @Test
    fun `F6_2 동의가 없으면 Gemini 에 보내지 않고 내장 풀 질문이 나온다`() = exam {
        val exam = launch(HttpStatusCode.OK)
        AppSettings(read = { prefs[it] }, write = { key, value -> prefs[key] = value }).revoke()
        exam.firstQuestion()
        exam.answerUntil(2)

        assertTrue(exam.play().startsWith("pool"))
        assertEquals(emptyList(), prompts)
    }

    @Test
    fun `F3_3 그래프를 다시 만들어 이어 보면 Gemini 가 실패해도 같은 질문이다`() = exam {
        val exam = launch(HttpStatusCode.OK)
        exam.firstQuestion()
        exam.answerUntil(2)
        val second = exam.play()
        viewModels.forEach(ViewModelStore::clear)

        val resumed = launch(HttpStatusCode.ServiceUnavailable)
        resumed.uiState.first { it.step is ExamStep.Ask }

        assertEquals("generated not chosen yet 2" to second, resumed.play() to second)
        assertEquals(1, prompts.size)
    }
}

/** 기기 음성 대신 읽은 글을 모은다. */
private class SpeechLog(private val spoken: MutableList<String>) : Tts {
    override suspend fun speak(text: String, audio: ByteArray?): Boolean = spoken.add(text)

    override fun hasHighQualityVoice() = true

    override fun stop() = Unit
}
