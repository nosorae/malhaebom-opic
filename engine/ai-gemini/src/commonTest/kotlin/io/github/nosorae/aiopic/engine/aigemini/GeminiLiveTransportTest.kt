package io.github.nosorae.aiopic.engine.aigemini

import io.github.nosorae.aiopic.engine.coach.Modality
import io.github.nosorae.aiopic.engine.coach.TokenUsage
import io.github.nosorae.aiopic.engine.coach.TransportEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveConnectException
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
import io.github.nosorae.aiopic.libs.livegemini.LiveSession
import io.github.nosorae.aiopic.libs.livegemini.LiveSocket
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Gemini Live 이벤트를 코치 연결 이벤트로 바꾼다(#265). 가짜 WebSocket 으로 서버 없이 돈다. */
class GeminiLiveTransportTest {
    private val sockets = mutableListOf<FakeSocket>()
    private var key: String? = "test-key"
    private val transport = GeminiLiveTransport(
        apiKey = { key },
        voice = "Kore",
        newSession = { LiveSession(open = { FakeSocket().also { sockets += it } }) },
    )

    /** 서버 프레임을 넣는 즉시 처리되도록 Unconfined 로 모으며 [block] 을 돌리고, 끝나면 모으기를 멈춘다. */
    private suspend fun TestScope.collect(resumeHandle: String? = null, block: suspend () -> Unit = {}): List<TransportEvent> {
        val events = mutableListOf<TransportEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { transport.connect("Coach.", emptyList(), resumeHandle).collect { events += it } }
        block()
        job.cancelAndJoin()
        return events
    }

    private fun server(text: String) = sockets.last().server(text)

    private fun setup(index: Int = 0) = Json.parseToJsonElement(sockets[index].sent.first()).jsonObject["setup"]!!.jsonObject

    @Test
    fun `받아쓰기와 도구 호출과 끼어들기와 턴 끝과 핸들을 코치 이벤트로 바꾼다`() = runTest {
        val events = collect {
            server("""{"setupComplete":{}}""")
            server("""{"serverContent":{"inputTranscription":{"text":"I went"}}}""")
            server("""{"serverContent":{"outputTranscription":{"text":"Where?"}}}""")
            server("""{"toolCall":{"functionCalls":[{"id":"t1","name":"save_material","args":{"kind":"place"}}]}}""")
            server("""{"serverContent":{"interrupted":true}}""")
            server("""{"serverContent":{"turnComplete":true}}""")
            server("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""")
        }

        assertEquals(
            listOf(
                TransportEvent.UserSaid("I went"),
                TransportEvent.CoachSaid("Where?"),
                TransportEvent.ToolCall("t1", "save_material", buildJsonObject { put("kind", "place") }),
                TransportEvent.Interrupted,
                TransportEvent.TurnComplete,
                TransportEvent.ResumeHandle("h1"),
            ),
            events,
        )
    }

    @Test
    fun `코치 음성 조각을 그대로 넘긴다`() = runTest {
        val pcm = byteArrayOf(1, 2, 3, 4)

        val events = collect {
            server("""{"setupComplete":{}}""")
            server("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"${Base64.encode(pcm)}"}}]}}}""")
        }

        assertContentEquals(pcm, assertIs<TransportEvent.Audio>(events.single()).pcm)
    }

    @Test
    fun `429 로 열지 못하면 한도 이유로 끊는다`() = runTest {
        val limited = GeminiLiveTransport({ "test-key" }, "Kore", newSession = { LiveSession(open = { throw LiveConnectException(429, "Too Many Requests") }) })

        val events = mutableListOf<TransportEvent>()
        limited.connect("Coach.", emptyList(), null).collect { events += it }

        assertEquals<List<TransportEvent>>(listOf(TransportEvent.Disconnected(TransportEvent.Disconnected.RATE_LIMITED)), events)
    }

    @Test
    fun `서버가 연결을 닫으면 네트워크 이유로 끊는다`() = runTest {
        val events = collect {
            server("""{"setupComplete":{}}""")
            sockets.last().serverClose()
        }

        assertEquals(listOf(TransportEvent.Disconnected(TransportEvent.Disconnected.NETWORK)), events)
    }

    @Test
    fun `키가 없으면 열지 않고 키 없음으로 끊는다`() = runTest {
        key = null

        val events = collect()

        assertEquals(listOf(TransportEvent.Disconnected(TransportEvent.Disconnected.NO_KEY)), events)
        assertTrue(sockets.isEmpty())
    }

    @Test
    fun `모델과 음성과 지시를 setup 에 넣고 받은 핸들로 이어 붙인다`() = runTest {
        collect(resumeHandle = "h1")

        assertEquals("models/${GeminiModels.liveModel.id}", setup()["model"].toString().trim('"'))
        assertEquals("""{"handle":"h1"}""", setup()["sessionResumption"].toString())
        assertTrue("Kore" in setup()["generationConfig"].toString())
        assertTrue("Coach." in setup()["systemInstruction"].toString())
    }

    @Test
    fun `ADR 0036 핸들 없이 다시 열면 앞 연결에서 받은 핸들로 이어 붙이지 않는다`() = runTest {
        collect {
            server("""{"setupComplete":{}}""")
            server("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""")
        }

        collect()

        assertEquals("{}", setup(1)["sessionResumption"].toString())
    }

    @Test
    fun `연결된 뒤 보낸 음성은 지금 소켓으로 간다`() = runTest {
        collect {
            server("""{"setupComplete":{}}""")
            transport.sendAudio(byteArrayOf(1, 2))
            transport.sendAudioEnd()
        }

        assertEquals(3, sockets.single().sent.size)
    }

    @Test
    fun `F12_4 usageMetadata 를 모달리티별 토큰으로 바꾼다`() = runTest {
        val events = collect {
            server("""{"setupComplete":{}}""")
            server(
                """{"usageMetadata":{"promptTokenCount":120,"responseTokenCount":80,
                   "promptTokensDetails":[{"modality":"AUDIO","tokenCount":100},{"modality":"TEXT","tokenCount":20}],
                   "responseTokensDetails":[{"modality":"AUDIO","tokenCount":80}]}}""",
            )
        }

        assertEquals(listOf(TransportEvent.Usage(TokenUsage(mapOf(Modality.Audio to 100, Modality.Text to 20), mapOf(Modality.Audio to 80)))), events)
    }

    @Test
    fun `F12_4 사용량이 줄지 않고 쌓이면 누적값으로 보고 차이만 넘긴다`() {
        val turns = TurnUsage()

        val sent = listOf(usage(100, 10), usage(250, 30), usage(400, 50)).map(turns::next)

        assertEquals(listOf(usage(100, 10), usage(150, 20), usage(150, 20)), sent)
    }

    @Test
    fun `F12_4 사용량이 한 번이라도 줄면 턴별 값으로 보고 앞에서 덜 센 만큼 더해 합을 맞춘다`() {
        val turns = TurnUsage()
        val received = listOf(usage(100, 10), usage(250, 30), usage(300, 5), usage(320, 40))

        val sent = received.map(turns::next)

        assertEquals(received.reduce(TokenUsage::plus), sent.reduce(TokenUsage::plus))
        assertEquals(usage(320, 40), sent.last())
    }

    private fun usage(input: Int, output: Int) = TokenUsage(mapOf(Modality.Audio to input), mapOf(Modality.Audio to output))
}

private class FakeSocket : LiveSocket {
    val sent = mutableListOf<String>()
    private val frames = Channel<String>(Channel.UNLIMITED)
    override var closeReason: LiveEvent.Disconnected? = null

    fun server(text: String) {
        frames.trySend(text)
    }

    fun serverClose() {
        closeReason = LiveEvent.Disconnected(1011, "internal")
        frames.close()
    }

    override suspend fun send(text: String) {
        sent += text
    }

    override suspend fun receive(): String? = frames.receiveCatching().getOrNull()

    override suspend fun close() {
        frames.close()
    }
}
