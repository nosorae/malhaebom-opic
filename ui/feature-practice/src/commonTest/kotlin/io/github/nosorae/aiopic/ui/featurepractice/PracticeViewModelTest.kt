package io.github.nosorae.aiopic.ui.featurepractice

import androidx.lifecycle.viewModelScope
import io.github.nosorae.aiopic.libs.timer.api.MonotonicTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PracticeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakePracticeRepository()
    private val tts = FakeTts(readTime = 3.seconds)
    private val recorder = FakeRecorder()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val created = mutableListOf<PracticeViewModel>()

    private fun viewModel(questionId: String? = null, script: String? = null) = PracticeViewModel(
        answerId = "a1",
        questionId = questionId,
        script = script,
        pickPracticeQuestionUseCase = PickPracticeQuestionUseCase(repository, typeNames = emptyMap()),
        playPracticeQuestionUseCase = PlayPracticeQuestionUseCase(repository, tts, recorder),
        submitPracticeAnswerUseCase = SubmitPracticeAnswerUseCase(repository, recorder),
        stopRecordingUseCase = StopRecordingUseCase(recorder),
        observeVoiceLevelUseCase = ObserveVoiceLevelUseCase(recorder),
        timer = MonotonicTimer { dispatcher.scheduler.currentTime.milliseconds },
    ).also { created += it }

    /** 녹음 중 시계는 1초마다 끝없이 돌아서, 테스트가 끝날 때 ViewModel 을 치우지 않으면 runTest 가 가상 시간을 끝없이 돌린다. */
    private fun practiceTest(body: suspend TestScope.() -> Unit): TestResult = runTest(dispatcher) {
        try {
            body()
        } finally {
            created.forEach { it.viewModelScope.cancel() }
        }
    }

    /** 질문을 고른 뒤 Play 를 누르고 질문을 다 읽어 녹음이 시작될 때까지 돌린다. 이 시점이 녹음 0초다. */
    private fun TestScope.playUntilRecording(viewModel: PracticeViewModel) {
        runCurrent()
        viewModel.onIntent(PracticeIntent.PlayQuestion)
        advance(3.seconds)
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `F14_5 스크립트로 연습하면 그 질문을 내고 스크립트를 보인다`() = practiceTest {
        val question = repository.pool().last()
        val viewModel = viewModel(questionId = question.id, script = "I went to Busan.")
        runCurrent()

        assertEquals(question to "I went to Busan.", viewModel.uiState.value.question to viewModel.uiState.value.script)
    }

    @Test
    fun `녹음하는 동안만 마이크 음량을 목소리 띠에 넘기고 다시 듣기로 녹음을 멈추면 0 으로 돌린다`() = practiceTest {
        val viewModel = viewModel()
        recorder.level.value = 0.9f
        runCurrent()
        assertEquals(0f, viewModel.uiState.value.voiceLevel)

        playUntilRecording(viewModel)
        recorder.level.value = 0.4f
        runCurrent()
        assertEquals(0.4f, viewModel.uiState.value.voiceLevel)

        viewModel.onIntent(PracticeIntent.ReplayQuestion)
        runCurrent()
        recorder.level.value = 0.7f
        runCurrent()

        assertEquals(0f, viewModel.uiState.value.voiceLevel)
    }

    @Test
    fun `241 답변에 녹음 내내 들은 가장 큰 음량을 남기고 말이 없었으면 0 이다`() = practiceTest {
        val spoken = viewModel()
        playUntilRecording(spoken)
        recorder.level.value = 0.4f
        runCurrent()
        recorder.level.value = 0f
        advance(NextDelay)
        spoken.onIntent(PracticeIntent.SubmitAnswer)
        runCurrent()
        assertEquals(0.4f, repository.answers.last().loudest)

        val silent = viewModel()
        playUntilRecording(silent)
        advance(NextDelay)
        silent.onIntent(PracticeIntent.SubmitAnswer)
        runCurrent()
        assertEquals(0f, repository.answers.last().loudest)
    }

    @Test
    fun `F4_1 질문을 고르면 Play 를 누를 수 있다`() = practiceTest {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.canPlay)

        runCurrent()

        assertTrue(viewModel.uiState.value.canPlay)
    }

    @Test
    fun `F4_1 약한 유형에서 고르면 왜 이 질문인지 유형 점수를 보여 준다`() = practiceTest {
        repository.answers += scored("old", question("q1", "routine"), 42)
        val viewModel = viewModel()
        runCurrent()

        assertEquals(PracticeWeakness("routine", 42), viewModel.uiState.value.weakness)
    }

    @Test
    fun `낼 질문이 없으면 이유를 보여 주고 Play 를 막는다`() = practiceTest {
        repository.pool = emptyList()
        val viewModel = viewModel()
        runCurrent()

        assertEquals(PracticeError.QuestionUnavailable, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.canPlay)
    }

    @Test
    fun `F4_1 Play 를 누르면 질문을 읽고 다 읽은 뒤에 녹음이 시작된다`() = practiceTest {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onIntent(PracticeIntent.PlayQuestion)
        runCurrent()
        assertEquals(PracticePhase.Speaking, viewModel.uiState.value.phase)
        assertNull(recorder.recording)

        advance(3.seconds)

        assertEquals(listOf("질문 q1"), tts.spoken)
        assertEquals(PracticePhase.Recording, viewModel.uiState.value.phase)
        assertEquals("rec/a1", recorder.recording)
    }

    @Test
    fun `F4_1 다시 듣기는 녹음 시작 5초 안에 한 번만 된다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(4.seconds)
        assertTrue(viewModel.uiState.value.canReplay)

        viewModel.onIntent(PracticeIntent.ReplayQuestion)
        advance(3.seconds)

        assertEquals(2, tts.spoken.size)
        assertEquals(PracticePhase.Recording, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.canReplay)
        viewModel.onIntent(PracticeIntent.ReplayQuestion)
        advance(3.seconds)
        assertEquals(2, tts.spoken.size)
    }

    @Test
    fun `F4_1 5초가 지나면 다시 듣기를 보내도 질문을 다시 읽지 않는다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(5.seconds)
        assertFalse(viewModel.uiState.value.canReplay)

        viewModel.onIntent(PracticeIntent.ReplayQuestion)
        advance(3.seconds)

        assertEquals(1, tts.spoken.size)
    }

    @Test
    fun `F4_1 Next 는 녹음 시작 10초 뒤에 켜지고 그 전에는 답변을 내지 않는다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(9.seconds)
        assertFalse(viewModel.uiState.value.canNext)
        viewModel.onIntent(PracticeIntent.SubmitAnswer)
        runCurrent()
        assertTrue(repository.answers.isEmpty())

        advance(1.seconds)

        assertTrue(viewModel.uiState.value.canNext)
    }

    @Test
    fun `F4_2 Next 를 누르면 답변을 저장하고 그 답변의 피드백으로 간다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(10.seconds)

        viewModel.onIntent(PracticeIntent.SubmitAnswer)
        runCurrent()

        val answer = repository.answers.single()
        assertEquals("a1", answer.id)
        assertEquals("q1", answer.question.id)
        assertNull(answer.examSessionId)
        assertEquals(listOf("rec/a1/0.m4a"), answer.chunkPaths)
        assertNull(recorder.recording)
        assertEquals(PracticeDestination.Feedback("a1"), viewModel.uiState.value.navigation)

        viewModel.onIntent(PracticeIntent.NavigationHandled)
        assertNull(viewModel.uiState.value.navigation)
    }

    @Test
    fun `Next 를 누르면 답변 저장이 끝날 때까지 처리 중이고 그동안 나가기를 받지 않는다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(10.seconds)
        repository.saveTime = 1.seconds

        viewModel.onIntent(PracticeIntent.SubmitAnswer)
        runCurrent()
        viewModel.onIntent(PracticeIntent.ExitPractice)
        runCurrent()

        assertTrue(viewModel.uiState.value.saving)
        assertNull(viewModel.uiState.value.navigation)
        advance(1.seconds)
        assertEquals(PracticeDestination.Feedback("a1"), viewModel.uiState.value.navigation)
        assertEquals(1, repository.answers.size)
    }

    @Test
    fun `질문을 고르는 동안과 질문을 읽는 동안 Play 는 처리 중이다`() = practiceTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.preparing)
        runCurrent()
        assertFalse(viewModel.uiState.value.preparing)

        viewModel.onIntent(PracticeIntent.PlayQuestion)
        runCurrent()

        assertTrue(viewModel.uiState.value.preparing)
    }

    @Test
    fun `마이크를 열지 못하면 녹음하지 않고 이유를 보여 준다`() = practiceTest {
        recorder.micAvailable = false
        val viewModel = viewModel()
        playUntilRecording(viewModel)

        assertEquals(PracticePhase.Ready, viewModel.uiState.value.phase)
        assertEquals(PracticeError.MicUnavailable, viewModel.uiState.value.error)
    }

    @Test
    fun `마이크 권한을 거절하면 이유를 보여 준다`() = practiceTest {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onIntent(PracticeIntent.MicDenied)

        assertEquals(PracticeError.MicDenied, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.canPlay)
    }

    @Test
    fun `질문을 읽지 못하면 녹음하지 않고 이유를 보여 준다`() = practiceTest {
        tts.canSpeak = false
        val viewModel = viewModel()
        playUntilRecording(viewModel)

        assertNull(recorder.recording)
        assertEquals(PracticeError.SpeechFailed, viewModel.uiState.value.error)
    }

    @Test
    fun `나가기를 누르면 녹음을 멈추고 답변을 남기지 않고 나간다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)

        viewModel.onIntent(PracticeIntent.ExitPractice)
        runCurrent()

        assertNull(recorder.recording)
        assertTrue(repository.answers.isEmpty())
        assertEquals(PracticeDestination.Exit, viewModel.uiState.value.navigation)
    }

    @Test
    fun `녹음 중 나가기를 누르면 저장하는 동안에도 답변 제출 중이 아니다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)

        viewModel.onIntent(PracticeIntent.ExitPractice)

        assertTrue(viewModel.uiState.value.saving)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `Next 를 누르면 저장하는 동안 답변 제출 중이다`() = practiceTest {
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(10.seconds)

        viewModel.onIntent(PracticeIntent.SubmitAnswer)

        assertTrue(viewModel.uiState.value.submitting)
        runCurrent()
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `녹음 조각을 쓰지 못했으면 Next 에서 답변을 저장하지 않고 이유를 보여 준다`() = practiceTest {
        recorder.writeFails = true
        val viewModel = viewModel()
        playUntilRecording(viewModel)
        advance(NextDelay)

        viewModel.onIntent(PracticeIntent.SubmitAnswer)
        runCurrent()

        assertEquals(PracticeError.RecordingFailed, viewModel.uiState.value.error)
        assertEquals(PracticePhase.Ready, viewModel.uiState.value.phase)
        assertTrue(repository.answers.isEmpty())
        assertNull(viewModel.uiState.value.navigation)
        assertTrue(viewModel.uiState.value.canPlay)
    }

    @Test
    fun `다시 듣기 전에 녹음 조각을 쓰지 못했으면 이어 녹음하지 않고 이유를 보여 준다`() = practiceTest {
        recorder.writeFails = true
        val viewModel = viewModel()
        playUntilRecording(viewModel)

        viewModel.onIntent(PracticeIntent.ReplayQuestion)
        runCurrent()

        assertEquals(PracticeError.RecordingFailed, viewModel.uiState.value.error)
        assertEquals(PracticePhase.Ready, viewModel.uiState.value.phase)
        assertNull(recorder.recording)
    }
}
