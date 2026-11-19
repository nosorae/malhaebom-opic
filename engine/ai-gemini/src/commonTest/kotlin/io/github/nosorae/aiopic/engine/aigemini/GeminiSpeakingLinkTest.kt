package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveSession
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 말하기 연습 연결을 Gemini Live 로 연다(F16). 가짜 WebSocket 으로 서버 없이 돈다. */
class GeminiSpeakingLinkTest {
    private val sockets = mutableListOf<FakeSocket>()
    private var key: String? = "test-key"
    private val link = GeminiSpeakingLink(apiKey = { key }, voice = "Kore", session = LiveSession(open = { FakeSocket().also { sockets += it } }))

    @Test
    fun `F16_24 쌓여 오는 사용량을 엔진이 더하는 턴별 값으로 바꿔 넘긴다`() = runTest {
        val events = mutableListOf<LiveEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { link.connect("Practice.", emptyList(), null, AudioRoute.Earphones, interruptible = true).collect { events += it } }

        sockets.last().server("""{"setupComplete":{}}""")
        sockets.last().server(usage(100, 10))
        sockets.last().server(usage(250, 30))
        job.cancelAndJoin()

        assertEquals(listOf(100 to 10, 150 to 20), events.filterIsInstance<LiveEvent.Usage>().map { it.promptByModality.getValue("AUDIO") to it.responseByModality.getValue("AUDIO") })
    }

    @Test
    fun `키가 없으면 연결하지 않고 흐름을 끝낸다`() = runTest {
        key = null

        val events = link.connect("Practice.", emptyList(), null, AudioRoute.Earphones, interruptible = true).toList()

        assertTrue(events.isEmpty() && sockets.isEmpty())
    }

    @Test
    fun `F16_17 설정의 말 끝 기다림과 말로 끊기 끔을 연결 setup 에 넣는다`() = runTest {
        val tuned = GeminiSpeakingLink(apiKey = { key }, voice = "Kore", silence = 1.5.seconds, session = LiveSession(open = { FakeSocket().also { sockets += it } }))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { tuned.connect("Practice.", emptyList(), null, AudioRoute.Earphones, interruptible = false).collect {} }

        val setup = sockets.last().sent.first()
        job.cancelAndJoin()

        assertTrue("\"silenceDurationMs\":1500" in setup && "NO_INTERRUPTION" in setup, setup)
    }

    @Test
    fun `F20_5 스피커 모드면 말 시작과 끝 감도를 낮게 둔다`() = runTest {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { link.connect("Practice.", emptyList(), null, AudioRoute.Speaker, interruptible = true).collect {} }

        val setup = sockets.last().sent.first()
        job.cancelAndJoin()

        assertTrue("START_SENSITIVITY_LOW" in setup && "END_SENSITIVITY_LOW" in setup, setup)
    }

    @Test
    fun `F20_5 이어폰이면 감도를 서버 기본값에 맡긴다`() = runTest {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { link.connect("Practice.", emptyList(), null, AudioRoute.Earphones, interruptible = true).collect {} }

        val setup = sockets.last().sent.first()
        job.cancelAndJoin()

        assertTrue("SENSITIVITY" !in setup, setup)
    }

    private fun usage(input: Int, output: Int) = """{"usageMetadata":{"promptTokensDetails":[{"modality":"AUDIO","tokenCount":$input}],"responseTokensDetails":[{"modality":"AUDIO","tokenCount":$output}]}}"""
}
