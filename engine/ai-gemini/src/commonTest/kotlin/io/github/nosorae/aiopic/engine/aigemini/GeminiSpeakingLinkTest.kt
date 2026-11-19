package io.github.nosorae.aiopic.engine.aigemini

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

/** 말하기 연습 연결을 Gemini Live 로 연다(F16). 가짜 WebSocket 으로 서버 없이 돈다. */
class GeminiSpeakingLinkTest {
    private val sockets = mutableListOf<FakeSocket>()
    private var key: String? = "test-key"
    private val link = GeminiSpeakingLink(apiKey = { key }, voice = "Kore", session = LiveSession(open = { FakeSocket().also { sockets += it } }))

    @Test
    fun `F16_24 쌓여 오는 사용량을 엔진이 더하는 턴별 값으로 바꿔 넘긴다`() = runTest {
        val events = mutableListOf<LiveEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { link.connect("Practice.", emptyList(), null).collect { events += it } }

        sockets.last().server("""{"setupComplete":{}}""")
        sockets.last().server(usage(100, 10))
        sockets.last().server(usage(250, 30))
        job.cancelAndJoin()

        assertEquals(listOf(100 to 10, 150 to 20), events.filterIsInstance<LiveEvent.Usage>().map { it.promptByModality.getValue("AUDIO") to it.responseByModality.getValue("AUDIO") })
    }

    @Test
    fun `키가 없으면 연결하지 않고 흐름을 끝낸다`() = runTest {
        key = null

        val events = link.connect("Practice.", emptyList(), null).toList()

        assertTrue(events.isEmpty() && sockets.isEmpty())
    }

    private fun usage(input: Int, output: Int) = """{"usageMetadata":{"promptTokensDetails":[{"modality":"AUDIO","tokenCount":$input}],"responseTokensDetails":[{"modality":"AUDIO","tokenCount":$output}]}}"""
}
