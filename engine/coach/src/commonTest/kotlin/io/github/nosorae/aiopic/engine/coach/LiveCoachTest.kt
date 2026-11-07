package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LiveCoachTest {
    private val profile = CoachProfile(
        instructions = mapOf(CoachPhase.Explore to "Explore.", CoachPhase.Express to "Express.", CoachPhase.Feedback to "Feedback."),
        tools = listOf(buildJsonObject { put("name", CoachTools.SAVE_MATERIAL) }),
    )
    private val transport = FakeTransport()
    private val audio = FakeAudio()

    private fun place(text: String) = buildJsonObject {
        put("kind", "place")
        put("text", text)
    }

    /** DB 쿼리와 코치의 1초 시계를 같은 테스트 시계 위에서 돌린다. */
    private fun test(block: suspend TestScope.(OpicDatabase) -> Unit) = runTest {
        val db = inMemoryDatabase(StandardTestDispatcher(testScheduler))
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun TestScope.coach(db: OpicDatabase, scope: CoroutineScope = backgroundScope) = LiveCoach(
        profile = profile,
        transport = transport,
        audio = audio,
        store = CoachStore(db),
        clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        },
        scope = scope,
    )

    private suspend fun TestScope.started(db: OpicDatabase, scope: CoroutineScope = backgroundScope) = coach(db, scope).also {
        it.start("c1", "q1", "IH")
        runCurrent()
    }

    @Test
    fun `시작하면 첫 단계 지시와 도구로 연결한다`() = test { db ->
        started(db)

        assertEquals(listOf(FakeTransport.Connect("Explore.", profile.tools, null)), transport.connects)
    }

    @Test
    fun `F11 침묵 조각은 보내지 않고 말하다 침묵이 시작되면 오디오 끝을 한 번 보낸다`() = test { db ->
        started(db)

        listOf(SILENT, LOUD, LOUD, SILENT, SILENT).forEach(audio::say)
        runCurrent()

        assertEquals(2, transport.audio.size)
        assertEquals(1, transport.audioEnds)
    }

    @Test
    fun `F11_4 끼어들기면 재생 대기열을 비운다`() = test { db ->
        started(db)

        transport.server(TransportEvent.Audio(LOUD))
        transport.server(TransportEvent.Interrupted)
        runCurrent()

        assertTrue(audio.queued.isEmpty())
    }

    @Test
    fun `받은 도구 호출과 버린 도구 호출 모두 응답을 보내고 버린 것에는 에러를 담는다`() = test { db ->
        val coach = started(db)

        transport.server(TransportEvent.ToolCall("t1", CoachTools.SAVE_MATERIAL, place("a cafe")))
        transport.server(TransportEvent.ToolCall("t2", CoachTools.SAVE_MATERIAL, place("")))
        runCurrent()

        assertEquals(listOf("t1", "t2"), transport.responses.map { it.first })
        assertNull(transport.responses[0].second.jsonObject["error"])
        assertNotNull(transport.responses[1].second.jsonObject["error"])
        assertEquals(listOf(Material(MaterialKind.Place, "a cafe")), coach.state.value?.materials)
    }

    @Test
    fun `F11_5 코치가 단계를 넘기면 메모를 담은 새 지시로 새로 연결한다`() = test { db ->
        started(db)

        transport.server(TransportEvent.ToolCall("t1", CoachTools.SAVE_MATERIAL, place("a cafe")))
        transport.server(TransportEvent.ToolCall("t2", CoachTools.ADVANCE_PHASE, JsonObject(emptyMap())))
        runCurrent()

        assertEquals("Express.\n\nNotes so far:\nplace: a cafe", transport.connects.last().instruction)
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `F13 리허설 단계에서는 연결을 닫고 리허설 받아쓰기를 피드백 지시에 넣는다`() = test { db ->
        val coach = started(db)

        coach.nextPhase()
        runCurrent()
        coach.nextPhase()
        runCurrent()
        coach.rehearsed("I usually go to a cafe.")
        coach.nextPhase()
        runCurrent()

        assertEquals(listOf("Explore.", "Express.", "Feedback.\n\nNotes so far:\nrehearsal 1: I usually go to a cafe."), transport.connects.map { it.instruction })
    }

    @Test
    fun `F11_7 일시정지한 시간은 대화 시간에서 빼고 30분이 되면 스크립트 정리로 간다`() = test { db ->
        val coach = started(db)

        advanceTimeBy(10.minutes)
        coach.pause()
        advanceTimeBy(5.minutes)
        coach.resume()
        advanceTimeBy(16.minutes)
        runCurrent()
        val warned = coach.state.value!!
        // 일시정지할 때 재던 1초 미만은 버려서 4분이 아니라 5분을 더 돌린다.
        advanceTimeBy(5.minutes)
        runCurrent()

        assertEquals(CoachPhase.Explore, warned.phase)
        assertTrue(warned.timeWarning)
        assertEquals(CoachPhase.Script, coach.state.value?.phase)
    }

    @Test
    fun `F11_6 일시정지하면 연결을 닫고 재개하면 받은 핸들로 이어 붙인다`() = test { db ->
        val coach = started(db)

        transport.server(TransportEvent.ResumeHandle("h1"))
        runCurrent()
        coach.pause()
        runCurrent()
        coach.resume()
        runCurrent()

        assertEquals(listOf(null, "h1"), transport.connects.map { it.resumeHandle })
    }

    @Test
    fun `F11_9 끊기면 받아쓰기와 메모는 남고 이유가 보이며 다시 연결할 수 있다`() = test { db ->
        val coach = started(db)

        transport.server(TransportEvent.UserSaid("I live in Seoul."))
        transport.server(TransportEvent.Disconnected("429"))
        runCurrent()
        val dropped = coach.state.value!!
        coach.resume()
        runCurrent()

        assertEquals("429", dropped.disconnected)
        assertEquals(1, dropped.lines.size)
        assertNull(coach.state.value?.disconnected)
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `F11_8 앱이 죽었다 살아나면 Room 에서 단계와 받아쓰기와 메모와 핸들을 되살려 이어 붙인다`() = test { db ->
        val app = CoroutineScope(backgroundScope.coroutineContext + Job())
        val before = started(db, app)
        transport.server(TransportEvent.UserSaid("I live in Seoul."))
        transport.server(TransportEvent.ToolCall("t1", CoachTools.SAVE_MATERIAL, place("Seoul")))
        transport.server(TransportEvent.Usage(TokenUsage(input = mapOf(Modality.Audio to 100))))
        runCurrent()
        before.nextPhase()
        runCurrent()
        transport.server(TransportEvent.CoachSaid("Let's practice."))
        transport.server(TransportEvent.ResumeHandle("h2"))
        advanceTimeBy(3.seconds)
        runCurrent()
        val saved = before.state.value
        app.cancel()

        val after = coach(db)
        assertTrue(after.open("c1"))
        runCurrent()

        assertEquals(saved, after.state.value)
        assertEquals("h2", transport.connects.last().resumeHandle)
    }

    @Test
    fun `F11_8 핸들을 받은 지 2시간이 지나 다시 열면 핸들 없이 메모로 새로 연결한다`() = test { db ->
        val app = CoroutineScope(backgroundScope.coroutineContext + Job())
        started(db, app)
        transport.server(TransportEvent.ToolCall("t1", CoachTools.SAVE_MATERIAL, place("Seoul")))
        transport.server(TransportEvent.ResumeHandle("h1"))
        runCurrent()
        app.cancel()
        advanceTimeBy(2.hours + 1.seconds)

        coach(db).open("c1")
        runCurrent()

        assertEquals(FakeTransport.Connect("Explore.\n\nNotes so far:\nplace: Seoul", profile.tools, null), transport.connects.last())
    }

    @Test
    fun `F12_2 사용자가 고친 메모와 표현 카드는 다시 열어도 남는다`() = test { db ->
        val app = CoroutineScope(backgroundScope.coroutineContext + Job())
        val before = started(db, app)
        transport.server(TransportEvent.ToolCall("t1", CoachTools.SAVE_MATERIAL, place("Seoul")))
        runCurrent()
        before.editMaterial(MaterialKind.Place, "Busan")
        before.editExpression(0, Expression("a", "b", "IH"))
        app.cancel()

        val after = coach(db)
        after.open("c1")

        assertEquals(listOf(Material(MaterialKind.Place, "Busan")), after.state.value?.materials)
    }

    @Test
    fun `없는 세션은 열지 못한다`() = test { db ->
        assertFalse(coach(db).open("none"))
    }

    @Test
    fun `F14_3 스크립트는 원본과 고친 판을 함께 저장하고 읽는다`() = test { db ->
        val store = CoachStore(db)
        val script = CoachScript(
            sessionId = "c1",
            original = ScriptParts("Intro.", "Body.", "Closing."),
            expressions = listOf(Expression("좋았어", "It was a blast", "IH")),
            edited = ScriptParts("Intro.", "Body, shorter.", "Closing."),
            editedAt = Instant.fromEpochSeconds(10),
            promptVersion = "v1",
            model = "test-model",
        )

        store.save(script)

        assertEquals(script, store.script("c1").first())
    }
}
