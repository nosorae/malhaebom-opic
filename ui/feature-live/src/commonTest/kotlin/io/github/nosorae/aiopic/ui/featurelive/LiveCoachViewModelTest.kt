package io.github.nosorae.aiopic.ui.featurelive

import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.time.Instant

class LiveCoachViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val transport = FakeTransport()
    private val audio = FakeAudio()
    private val consent = FakeLiveConsent(given = true)
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

    private fun TestScope.viewModel(db: OpicDatabase) = LiveCoachViewModel(
        sessionId = "c1",
        questionId = "q1",
        question = "Tell me about a trip.",
        targetGrade = "IH",
        checkLiveConsentUseCase = CheckLiveConsentUseCase(consent),
        giveLiveConsentUseCase = GiveLiveConsentUseCase(consent),
        openCoachUseCase = OpenCoachUseCase { _, _, scope -> coach(db, scope) },
        observeCoachLevelsUseCase = ObserveCoachLevelsUseCase(audio),
    ).also { backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { _ -> it.viewModelScope.cancel() } }

    private fun TestScope.connected(db: OpicDatabase) = viewModel(db).also {
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
        runCurrent()
        val paused = viewModel.uiState.value.coach?.status
        viewModel.onIntent(LiveCoachIntent.ReturnToApp)
        runCurrent()

        assertEquals(CoachStatus.Paused to CoachStatus.Active, paused to viewModel.uiState.value.coach?.status)
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
