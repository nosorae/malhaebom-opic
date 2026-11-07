package io.github.nosorae.aiopic.apps.opic.shared

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nosorae.aiopic.apps.opic.shared.data.FakeWorkQueue
import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.question.model.Question
import io.github.nosorae.aiopic.engine.session.model.ExamSession
import io.github.nosorae.aiopic.engine.session.model.ExamStatus
import io.github.nosorae.aiopic.engine.session.model.ExamStep
import io.github.nosorae.aiopic.packs.opicen.OpicQuestionType
import io.github.nosorae.aiopic.ui.featureexam.ExamDestination
import io.github.nosorae.aiopic.ui.featureexam.ExamIntent
import io.github.nosorae.aiopic.ui.featureexam.ExamViewModel
import io.github.nosorae.aiopic.ui.featureexam.QuestionPhase
import io.github.nosorae.aiopic.ui.featurereport.SessionReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 이어 본 시험에서 40분 타이머가 다시 흐르는지 앱 그래프로 본다(F2.5, F2.6, F3.1).
 * 강제 종료는 ViewModel 을 치우고 그래프를 버린 뒤 같은 DB 와 설정으로 그래프를 새로 만드는 것으로 본다.
 * 시계는 테스트가 손으로 넘기는 단조 시계라서 꺼져 있던 동안 넘긴 시간이 남은 시간에서 빠지면 실패한다.
 */
class ResumeTimerTest {
    private val dispatcher = StandardTestDispatcher()

    // 앱을 다시 켜도 남는 것: 설정, DB
    private val db = inMemoryDatabase()
    private val prefs = mutableMapOf<String, String>()
    private val viewModels = mutableListOf<ViewModelStore>()
    private var now = Duration.ZERO
    private var graph: AppGraph? = null

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

    /** 앱을 새로 켜고 회차 s1 의 시험 화면을 연다. 그래프는 매번 새로 만든다. */
    private fun launch(): ExamViewModel {
        val created = createAppGraph(
            settings = AppSettings(read = { prefs[it] }, write = { key, value -> prefs[key] = value }),
            keyVault = FakeKeyVault(),
            database = db,
            recordingsRoot = "recordings",
            recorder = FakeRecorder,
            tts = FakeTts,
            workQueue = FakeWorkQueue(),
            duplexAudio = FakeDuplexAudio,
            timer = { now },
            pack = ExamPack(pool = { pool }, voice = { null }),
        ).also { graph = it }
        val store = ViewModelStore().also(viewModels::add)
        return ViewModelProvider.create(store, viewModelFactory { initializer { created.examViewModelFactory.create("s1") } })[ExamViewModel::class]
    }

    /** 프로세스가 죽은 것처럼 화면의 작업을 모두 멈추고 그래프를 버린다. */
    private fun kill() {
        viewModels.forEach(ViewModelStore::clear)
        viewModels.clear()
        graph = null
    }

    private suspend fun saved(): ExamSession = graph!!.examSessionSource.sessions().first().single()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            kill()
        }
    }

    /** 질문을 듣고 녹음을 시작한 뒤 11초를 넘겨 낸다. 다음 단계로 넘어가거나 시험이 끝날 때까지 기다린다. */
    private suspend fun ExamViewModel.answer() {
        onIntent(ExamIntent.PlayQuestion)
        uiState.first { it.phase == QuestionPhase.Recording }
        now += 11.seconds
        onIntent(ExamIntent.SubmitAnswer)
        uiState.first { it.phase != QuestionPhase.Recording || it.navigation != null }
    }

    /** 서베이, 수준 1, 점검을 지나 1번 문항에 답하고 2번 문항 앞에 선다. 40분은 1번 문항에서 켜진다. */
    private suspend fun ExamViewModel.toSecondQuestion() {
        uiState.first { it.step is ExamStep.Survey && it.survey != null }
        submitSurvey(topics)
        uiState.first { it.step == ExamStep.LevelSelect }
        onIntent(ExamIntent.SelectLevel(1))
        onIntent(ExamIntent.SubmitLevel)
        uiState.first { it.step == ExamStep.Check }
        answer()
        uiState.first { it.questionNumber == 1 && it.phase == QuestionPhase.Ready }
        answer()
        uiState.first { it.questionNumber == 2 && it.phase == QuestionPhase.Ready }
        // 1번 문항에서 쓴 11초가 다음 시계 틱에 회차에 들어간다
        uiState.first { it.remaining == 40.minutes - 11.seconds }
    }

    @Test
    fun `F2_5 꺼져 있던 시간은 빼고 이어 보면 남은 시간이 다시 줄고 문항 화면을 벗어나면 멈춘다`() = test {
        launch().toSecondQuestion()
        val beforeKill = saved().remaining!!
        assertEquals(40.minutes - 11.seconds, beforeKill)
        kill()

        // 꺼져 있던 10분은 빠진다
        now += 10.minutes
        val resumed = launch()
        assertEquals(2, resumed.uiState.first { it.step != null }.questionNumber)
        assertEquals(beforeKill, resumed.uiState.value.remaining)

        // 문항 화면에 있는 동안은 다시 흐른다
        now += 5.seconds
        resumed.uiState.first { it.remaining == beforeKill - 5.seconds }
        assertEquals(beforeKill - 5.seconds, saved().remaining)

        // 나가면 멈춘다
        resumed.onIntent(ExamIntent.ExitExam)
        resumed.uiState.first { it.navigation == ExamDestination.Exit }
        now += 10.minutes
        advanceTimeBy(5.seconds)
        assertEquals(beforeKill - 5.seconds, saved().remaining)
    }

    @Test
    fun `F2_6 이어 본 뒤 40분을 넘기면 시간 부족으로 끝나고 답하지 못한 문항이 시간 부족이 된다`() = test {
        launch().toSecondQuestion()
        kill()

        now += 10.minutes
        val resumed = launch()
        resumed.uiState.first { it.questionNumber == 2 }
        now += 40.minutes
        assertEquals(ExamDestination.Finished, resumed.uiState.first { it.navigation != null }.navigation)

        val session = saved()
        assertEquals(ExamStatus.TimeUp, session.status)
        assertEquals(Duration.ZERO, session.remaining)
        val report = ViewModelProvider.create(
            ViewModelStore().also(viewModels::add),
            viewModelFactory { initializer { graph!!.sessionReportViewModelFactory.create("s1") } },
        )[SessionReportViewModel::class].uiState.first { it.report != null }.report!!
        assertEquals(12, report.questionCount)
        assertEquals(1, report.answers.size)
        assertEquals(11, report.unansweredCount)
    }
}
