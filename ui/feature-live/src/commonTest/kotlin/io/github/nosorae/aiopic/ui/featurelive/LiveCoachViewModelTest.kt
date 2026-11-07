package io.github.nosorae.aiopic.ui.featurelive

import androidx.lifecycle.viewModelScope
import io.github.nosorae.aiopic.engine.answer.model.UnscorableReason
import io.github.nosorae.aiopic.engine.answer.port.AnalysisResult
import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.coach.CoachPhase
import io.github.nosorae.aiopic.engine.coach.CoachProfile
import io.github.nosorae.aiopic.engine.coach.CoachStatus
import io.github.nosorae.aiopic.engine.coach.CoachStore
import io.github.nosorae.aiopic.engine.coach.CoachTools
import io.github.nosorae.aiopic.engine.coach.Expression
import io.github.nosorae.aiopic.engine.coach.LiveCoach
import io.github.nosorae.aiopic.engine.coach.Material
import io.github.nosorae.aiopic.engine.coach.MaterialKind
import io.github.nosorae.aiopic.engine.coach.TransportEvent
import io.github.nosorae.aiopic.engine.session.model.AnalysisError
import io.github.nosorae.aiopic.libs.timer.api.MonotonicTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LiveCoachViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val transport = FakeTransport()
    private val audio = FakeAudio()
    private val consent = FakeLiveConsent(given = true)
    private val recorder = FakeRecorder()
    private val files = FakeRehearsalFiles(recorder, heard("I went to Busan."))
    private val profile = CoachProfile(instructions = CoachPhase.entries.associateWith { it.name }, tools = emptyList())

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** 코치의 1초 시계가 끝없이 돌아서 시간을 통째로 넘기지 않고 runCurrent 로만 돌린다. 끝나면 ViewModel 범위를 닫는다. */
    private fun test(block: suspend TestScope.(OpicDatabase) -> Unit) = runTest(dispatcher) {
        val db = inMemoryDatabase(dispatcher)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun TestScope.coach(db: OpicDatabase, scope: CoroutineScope) = LiveCoach(
        profile = profile,
        transport = transport,
        audio = audio,
        store = CoachStore(db),
        clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        },
        scope = scope,
    )

    private fun TestScope.viewModel(db: OpicDatabase, files: RehearsalFiles = this@LiveCoachViewModelTest.files): LiveCoachViewModel {
        val running = RunningCoach { _, _, scope -> coach(db, scope) }
        val transcribe = TranscribeRehearsalUseCase(files, running)
        return LiveCoachViewModel(
            sessionId = "c1",
            questionId = "q1",
            question = "Tell me about a trip.",
            targetGrade = "IH",
            checkLiveConsentUseCase = CheckLiveConsentUseCase(consent),
            giveLiveConsentUseCase = GiveLiveConsentUseCase(consent),
            openCoachUseCase = OpenCoachUseCase(running),
            applyCoachEventUseCase = ApplyCoachEventUseCase(running),
            observeCoachLevelsUseCase = ObserveCoachLevelsUseCase(audio),
            startRehearsalUseCase = StartRehearsalUseCase(recorder, files),
            stopRehearsalUseCase = StopRehearsalUseCase(recorder),
            finishRehearsalUseCase = FinishRehearsalUseCase(StopRehearsalUseCase(recorder), transcribe),
            transcribeRehearsalUseCase = transcribe,
            observeRehearsalLevelUseCase = ObserveRehearsalLevelUseCase(recorder),
            timer = MonotonicTimer { testScheduler.currentTime.milliseconds },
        ).also { backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { _ -> it.viewModelScope.cancel() } }
    }

    private fun TestScope.connected(db: OpicDatabase, files: RehearsalFiles = this@LiveCoachViewModelTest.files) = viewModel(db, files).also {
        runCurrent()
        it.onIntent(LiveCoachIntent.Connect)
        runCurrent()
    }

    @Test
    fun `F15_1 실시간 음성 전송에 동의하지 않았으면 연결해도 세션을 시작하지 않는다`() = test { db ->
        consent.given = false

        val viewModel = connected(db)

        assertEquals(false, viewModel.uiState.value.consented)
        assertNull(viewModel.uiState.value.coach)
        assertTrue(transport.connects.isEmpty())
    }

    @Test
    fun `F15_1 동의하면 저장하고 그 뒤 연결하면 세션을 시작한다`() = test { db ->
        consent.given = false
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.GiveConsent)
        runCurrent()
        viewModel.onIntent(LiveCoachIntent.Connect)
        runCurrent()

        assertTrue(consent.given)
        assertEquals(CoachPhase.Explore, viewModel.uiState.value.coach?.phase)
        assertEquals(1, transport.connects.size)
    }

    @Test
    fun `마이크 권한을 거절하면 세션을 열지 않고 거절 상태가 된다`() = test { db ->
        val viewModel = viewModel(db)
        runCurrent()

        viewModel.onIntent(LiveCoachIntent.MicDenied)
        runCurrent()

        assertTrue(viewModel.uiState.value.micDenied)
        assertTrue(transport.connects.isEmpty())
    }

    @Test
    fun `연결을 두 번 해도 세션은 하나만 연다`() = test { db ->
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.Connect)
        runCurrent()

        assertEquals(1, transport.connects.size)
    }

    @Test
    fun `F11_8 화면을 떠났다 다시 들어오면 저장된 세션을 그 단계에서 이어 간다`() = test { db ->
        val before = connected(db)
        before.onIntent(LiveCoachIntent.NextPhase)
        runCurrent()
        before.viewModelScope.cancel()

        val after = connected(db)

        assertEquals(CoachPhase.Express, after.uiState.value.coach?.phase)
    }

    @Test
    fun `F11_5 다음 단계를 고르면 다음 단계로 간다`() = test { db ->
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.NextPhase)
        runCurrent()

        assertEquals(CoachPhase.Express, viewModel.uiState.value.coach?.phase)
    }

    @Test
    fun `F11_6 잠깐 생각하면 멈추고 다시 누르면 이어 붙인다`() = test { db ->
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.Pause)
        runCurrent()
        val paused = viewModel.uiState.value.coach?.status
        viewModel.onIntent(LiveCoachIntent.Resume)
        runCurrent()

        assertEquals(CoachStatus.Paused to CoachStatus.Active, paused to viewModel.uiState.value.coach?.status)
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `F11_8 앱이 뒤로 가면 멈추고 돌아오면 이어 간다`() = test { db ->
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.LeaveApp)
        advanceTimeBy(LEAVE_GRACE + 1.milliseconds)
        runCurrent()
        val paused = viewModel.uiState.value.coach?.status
        viewModel.onIntent(LiveCoachIntent.ReturnToApp)
        runCurrent()

        assertEquals(CoachStatus.Paused to CoachStatus.Active, paused to viewModel.uiState.value.coach?.status)
    }

    @Test
    fun `F11_8 화면 회전처럼 잠깐 멈췄다 바로 돌아오면 연결을 끊지 않는다`() = test { db ->
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.LeaveApp)
        advanceTimeBy(LEAVE_GRACE / 2)
        viewModel.onIntent(LiveCoachIntent.ReturnToApp)
        advanceTimeBy(LEAVE_GRACE)
        runCurrent()

        assertEquals(CoachStatus.Active to 1, viewModel.uiState.value.coach?.status to transport.connects.size)
    }

    @Test
    fun `F11_8 사용자가 멈춘 세션은 앱이 돌아와도 멈춘 채로 둔다`() = test { db ->
        val viewModel = connected(db)
        viewModel.onIntent(LiveCoachIntent.Pause)
        runCurrent()

        viewModel.onIntent(LiveCoachIntent.LeaveApp)
        runCurrent()
        viewModel.onIntent(LiveCoachIntent.ReturnToApp)
        runCurrent()

        assertEquals(CoachStatus.Paused, viewModel.uiState.value.coach?.status)
    }

    @Test
    fun `F11_9 끊기면 받아쓰기와 이유가 남고 다시 연결하면 새로 연다`() = test { db ->
        val viewModel = connected(db)
        transport.server(TransportEvent.UserSaid("I went to Busan."))
        transport.server(TransportEvent.Disconnected(TransportEvent.Disconnected.RATE_LIMITED))
        runCurrent()
        val dropped = viewModel.uiState.value.coach

        viewModel.onIntent(LiveCoachIntent.Resume)
        runCurrent()

        assertEquals(TransportEvent.Disconnected.RATE_LIMITED, dropped?.disconnected)
        assertEquals(1, dropped?.lines?.size)
        assertNull(viewModel.uiState.value.coach?.disconnected)
        assertEquals(2, transport.connects.size)
    }

    private fun TestScope.rehearsing(db: OpicDatabase, files: RehearsalFiles = this@LiveCoachViewModelTest.files) = connected(db, files).also {
        repeat(2) { _ ->
            it.onIntent(LiveCoachIntent.NextPhase)
            runCurrent()
        }
    }

    private fun TestScope.rehearse(viewModel: LiveCoachViewModel) {
        viewModel.onIntent(RehearsalIntent.StartRecording)
        runCurrent()
        viewModel.onIntent(RehearsalIntent.StopRecording)
        runCurrent()
    }

    @Test
    fun `F13_1 녹음하는 동안 목소리 크기와 녹음 시간이 보이고 코치 연결은 열지 않는다`() = test { db ->
        val viewModel = rehearsing(db)

        viewModel.onIntent(RehearsalIntent.StartRecording)
        runCurrent()
        recorder.level.value = 0.5f
        advanceTimeBy(3_001)

        assertEquals(RehearsalUiState(RehearsalStep.Recording, 3.seconds, 0.5f), viewModel.uiState.value.rehearsal)
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `F13_2 녹음을 끝내면 받아쓰기만 받아 코치에 넣고 피드백 단계로 새로 연결하며 녹음을 지운다`() = test { db ->
        val viewModel = rehearsing(db)

        rehearse(viewModel)

        assertEquals(listOf("I went to Busan."), viewModel.uiState.value.coach?.rehearsals)
        assertEquals(CoachPhase.Feedback, viewModel.uiState.value.coach?.phase)
        assertEquals(3, transport.connects.size)
        assertEquals(RehearsalUiState(), viewModel.uiState.value.rehearsal)
        assertTrue(recorder.folders.isEmpty())
    }

    @Test
    fun `F13_1 받아쓰기를 못 받으면 이유를 보이고 다시 시도하면 같은 녹음을 다시 보낸다`() = test { db ->
        val files = FakeRehearsalFiles(recorder, AnalysisResult.Failed(AnalysisError.Offline), heard("Second try."))
        val viewModel = rehearsing(db, files)

        rehearse(viewModel)
        val failed = viewModel.uiState.value.rehearsal
        viewModel.onIntent(RehearsalIntent.RetryTranscript)
        runCurrent()

        assertEquals(RehearsalError.TranscriptFailed, failed.error)
        assertTrue(failed.canRetryTranscript)
        assertEquals(listOf(listOf("coach/c1/0.m4a"), listOf("coach/c1/0.m4a")), files.sent)
        assertEquals(listOf("Second try."), viewModel.uiState.value.coach?.rehearsals)
    }

    @Test
    fun `F13_1 말소리가 없으면 다시 녹음하게 하고 리허설 단계에 남는다`() = test { db ->
        val viewModel = rehearsing(db, FakeRehearsalFiles(recorder, AnalysisResult.Unscorable(UnscorableReason.Silent)))

        rehearse(viewModel)

        assertEquals(RehearsalError.NoSpeech, viewModel.uiState.value.rehearsal.error)
        assertEquals(CoachPhase.Rehearse, viewModel.uiState.value.coach?.phase)
    }

    @Test
    fun `F13_3 첫 리허설을 건너뛰면 피드백 없이 스크립트 정리로 간다`() = test { db ->
        val viewModel = rehearsing(db)

        viewModel.onIntent(LiveCoachIntent.NextPhase)
        runCurrent()

        assertEquals(CoachPhase.Script to CoachStatus.Finished, viewModel.uiState.value.coach?.let { it.phase to it.status })
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `F14_1 세션이 끝나면 스크립트로 가는 이동을 남기고 화면이 처리하면 지운다`() = test { db ->
        val viewModel = rehearsing(db)
        viewModel.onIntent(LiveCoachIntent.NextPhase)
        runCurrent()
        assertEquals(LiveCoachDestination.Script, viewModel.uiState.value.navigation)

        viewModel.onIntent(LiveCoachIntent.NavigationHandled)
        runCurrent()

        assertNull(viewModel.uiState.value.navigation)
    }

    @Test
    fun `F13_3 두 번째 리허설을 건너뛰면 첫 받아쓰기로 피드백 단계에 다시 연결한다`() = test { db ->
        val viewModel = rehearsing(db)
        rehearse(viewModel)
        viewModel.onIntent(RehearsalIntent.RehearseAgain)
        runCurrent()

        viewModel.onIntent(LiveCoachIntent.NextPhase)
        runCurrent()

        assertEquals(CoachPhase.Feedback to listOf("I went to Busan."), viewModel.uiState.value.coach?.let { it.phase to it.rehearsals })
        assertEquals(4, transport.connects.size)
    }

    @Test
    fun `마이크를 못 열면 이유를 보이고 녹음 중이 되지 않는다`() = test { db ->
        recorder.micAvailable = false
        val viewModel = rehearsing(db)

        viewModel.onIntent(RehearsalIntent.StartRecording)
        runCurrent()

        assertEquals(RehearsalUiState(error = RehearsalError.MicUnavailable), viewModel.uiState.value.rehearsal)
    }

    @Test
    fun `F13_3 피드백에서 한 번 더 리허설하면 리허설 단계로 돌아가고 두 번 하면 더 돌아가지 않는다`() = test { db ->
        val viewModel = rehearsing(db)
        rehearse(viewModel)

        viewModel.onIntent(RehearsalIntent.RehearseAgain)
        runCurrent()
        val again = viewModel.uiState.value.coach?.phase
        rehearse(viewModel)
        viewModel.onIntent(RehearsalIntent.RehearseAgain)
        runCurrent()

        assertEquals(CoachPhase.Rehearse, again)
        assertEquals(CoachPhase.Feedback, viewModel.uiState.value.coach?.phase)
        assertEquals(2, viewModel.uiState.value.coach?.rehearsals?.size)
    }

    @Test
    fun `녹음 중에 화면을 떠나면 녹음을 멈춘다`() = test { db ->
        val viewModel = rehearsing(db)
        viewModel.onIntent(RehearsalIntent.StartRecording)
        runCurrent()

        viewModel.viewModelScope.cancel()
        runCurrent()

        assertNull(recorder.recording)
    }

    @Test
    fun `F12_2 소재 칸을 고치면 그 메모가 바뀐다`() = test { db ->
        val viewModel = connected(db)

        viewModel.onIntent(LiveCoachIntent.EditMaterial(MaterialKind.Feeling, "relieved"))
        runCurrent()

        assertEquals(listOf(Material(MaterialKind.Feeling, "relieved")), viewModel.uiState.value.coach?.materials)
    }

    @Test
    fun `F12_2 표현 카드는 영어 표현만 고치고 한 말과 등급은 그대로 둔다`() = test { db ->
        val viewModel = connected(db)
        transport.server(
            TransportEvent.ToolCall(
                "t1",
                CoachTools.SAVE_EXPRESSION,
                buildJsonObject {
                    put("user_said", "좋았어")
                    put("english", "It was good")
                    put("grade", "IM2")
                },
            ),
        )
        runCurrent()

        viewModel.onIntent(LiveCoachIntent.EditExpression(0, " It was a blast "))
        runCurrent()

        assertEquals(listOf(Expression("좋았어", "It was a blast", "IM2")), viewModel.uiState.value.coach?.expressions)
    }

    @Test
    fun `F12_1 코치 목소리가 나오면 코치 쪽이 말하는 중이다`() = test { db ->
        val viewModel = connected(db)

        audio.playbackLevel.value = 0.6f
        runCurrent()

        assertTrue(viewModel.uiState.value.coachSpeaking)
    }

    @Test
    fun `조립 때 받은 질문 글을 화면 상태에 둔다`() = test { db ->
        val viewModel = viewModel(db)

        assertEquals("Tell me about a trip.", viewModel.uiState.value.question)
    }
}
