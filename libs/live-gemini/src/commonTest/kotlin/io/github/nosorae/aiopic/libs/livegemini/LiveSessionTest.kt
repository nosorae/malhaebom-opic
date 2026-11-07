package io.github.nosorae.aiopic.libs.livegemini

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LiveSessionTest {
    private val config = LiveConfig(
        model = "test-model",
        systemInstruction = "Coach the user.",
        voice = "Kore",
        tools = listOf(buildJsonObject { put("name", "save_script") }),
    )

    private val clock = object : Clock {
        var now = Instant.fromEpochSeconds(0)

        override fun now() = now
    }

    private val sockets = mutableListOf<FakeSocket>()
    private val urls = mutableListOf<String>()
    private val session = LiveSession(
        open = { url ->
            urls += url
            FakeSocket().also { sockets += it }
        },
        clock = clock,
    )

    /** 서버 프레임을 넣는 즉시 세션이 처리하도록 Unconfined 로 모으며 [block] 을 돌리고, 끝나면 모으기를 멈춘다. */
    private suspend fun TestScope.collect(resumeHandle: String? = null, resumeHandleAt: Instant? = null, block: suspend () -> Unit): List<LiveEvent> {
        val events = mutableListOf<LiveEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { session.connect("test-key", config, resumeHandle, resumeHandleAt).collect { events += it } }
        block()
        job.cancelAndJoin()
        return events
    }

    private fun FakeSocket.sentJson(index: Int): JsonObject = Json.parseToJsonElement(sent[index]).jsonObject

    @Test
    fun `키를 쿼리에 넣은 BidiGenerateContent 주소로 연다`() = runTest {
        collect { }

        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=test-key",
            urls.single(),
        )
    }

    @Test
    fun `첫 메시지로 모델과 음성과 지시와 도구와 받아쓰기와 맥락 압축을 담은 setup 을 보낸다`() = runTest {
        collect { }

        val setup = sockets.single().sentJson(0)["setup"]!!.jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """
                {"model":"models/test-model",
                 "generationConfig":{"responseModalities":["AUDIO"],"speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Kore"}}}},
                 "systemInstruction":{"parts":[{"text":"Coach the user."}]},
                 "tools":[{"functionDeclarations":[{"name":"save_script"}]}],
                 "inputAudioTranscription":{},"outputAudioTranscription":{},
                 "contextWindowCompression":{"triggerTokens":12000,"slidingWindow":{"targetTokens":6000}},
                 "sessionResumption":{}}
                """,
            ),
            setup,
        )
    }

    @Test
    fun `setupComplete 를 받기 전에는 오디오를 보내지 않는다`() = runTest {
        collect { session.sendAudio(byteArrayOf(1, 2)) }

        assertEquals(1, sockets.single().sent.size)
    }

    @Test
    fun `setupComplete 를 받으면 연결됨을 알리고 오디오를 base64 16kHz PCM 으로 보낸다`() = runTest {
        val events = collect {
            sockets.single().server("""{"setupComplete":{}}""")
            session.sendAudio(byteArrayOf(1, 2, 3))
        }

        assertEquals(listOf<LiveEvent>(LiveEvent.Connected), events)
        assertEquals(
            Json.parseToJsonElement("""{"realtimeInput":{"audio":{"data":"${Base64.encode(byteArrayOf(1, 2, 3))}","mimeType":"audio/pcm;rate=16000"}}}"""),
            sockets.single().sentJson(1),
        )
    }

    @Test
    fun `글과 오디오 끝과 도구 응답을 정해진 모양으로 보낸다`() = runTest {
        collect {
            sockets.single().server("""{"setupComplete":{}}""")
            session.sendText("hi")
            session.sendAudioEnd()
            session.sendToolResponse("call-1", "save_script", buildJsonObject { put("ok", true) })
        }

        val socket = sockets.single()
        assertEquals(
            listOf(
                """{"realtimeInput":{"text":"hi"}}""",
                """{"realtimeInput":{"audioStreamEnd":true}}""",
                """{"toolResponse":{"functionResponses":[{"id":"call-1","name":"save_script","response":{"result":{"ok":true}}}]}}""",
            ).map(Json::parseToJsonElement),
            (1..3).map { socket.sentJson(it) },
        )
    }

    @Test
    fun `서버 내용의 음성과 받아쓰기와 턴 끝과 끼어들기를 차례로 낸다`() = runTest {
        val audio = byteArrayOf(9, 8, 7)
        val events = collect {
            sockets.single().server(
                """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${Base64.encode(audio)}"}}]},
                    "inputTranscription":{"text":"I like"},"outputTranscription":{"text":"Great"},"interrupted":true,"turnComplete":true}}""",
            )
        }

        assertContentEquals(audio, assertIs<LiveEvent.AudioChunk>(events[0]).pcm)
        assertEquals(
            listOf(LiveEvent.InputTranscript("I like"), LiveEvent.OutputTranscript("Great"), LiveEvent.Interrupted, LiveEvent.TurnComplete),
            events.drop(1),
        )
    }

    @Test
    fun `도구 호출과 취소를 낸다`() = runTest {
        val events = collect {
            sockets.single().server("""{"toolCall":{"functionCalls":[{"id":"c1","name":"save_script","args":{"text":"x"}}]}}""")
            sockets.single().server("""{"toolCallCancellation":{"ids":["c1"]}}""")
        }

        assertEquals(
            listOf(
                LiveEvent.ToolCall("c1", "save_script", buildJsonObject { put("text", "x") }),
                LiveEvent.ToolCallCancelled(listOf("c1")),
            ),
            events,
        )
    }

    @Test
    fun `usageMetadata 를 입력 출력 토큰과 모달리티별 토큰으로 낸다`() = runTest {
        val events = collect {
            sockets.single().server(
                """{"usageMetadata":{"promptTokenCount":120,"responseTokenCount":80,"totalTokenCount":200,
                    "promptTokensDetails":[{"modality":"AUDIO","tokenCount":100},{"modality":"TEXT","tokenCount":20}],
                    "responseTokensDetails":[{"modality":"AUDIO","tokenCount":80}]}}""",
            )
        }

        assertEquals(listOf<LiveEvent>(LiveEvent.Usage(120, 80, mapOf("AUDIO" to 100, "TEXT" to 20), mapOf("AUDIO" to 80))), events)
    }

    @Test
    fun `한 메시지에 턴 끝과 사용량이 함께 오면 사용량을 먼저 내 턴 끝에 닫아도 사용량이 남는다`() = runTest {
        val events = collect {
            sockets.single().server("""{"serverContent":{"turnComplete":true},"usageMetadata":{"promptTokenCount":1,"responseTokenCount":2}}""")
        }

        assertEquals(listOf(LiveEvent.Usage(1, 2, emptyMap(), emptyMap()), LiveEvent.TurnComplete), events)
    }

    @Test
    fun `GoAway 를 받으면 마지막 핸들로 새 연결을 열고 이어 붙었음을 알린다`() = runTest {
        val events = collect {
            sockets[0].server("""{"setupComplete":{}}""")
            sockets[0].server("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""")
            sockets[0].server("""{"sessionResumptionUpdate":{"newHandle":"","resumable":false}}""")
            sockets[0].server("""{"goAway":{"timeLeft":"50s"}}""")
            sockets[1].server("""{"setupComplete":{}}""")
        }

        assertEquals(listOf(LiveEvent.Connected, LiveEvent.ResumeHandle("h1"), LiveEvent.GoAway(50.seconds), LiveEvent.Resumed), events)
        assertTrue(sockets[0].closedByClient)
        assertEquals("h1", sockets[1].sentJson(0)["setup"]!!.jsonObject["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `핸들 없이 GoAway 뒤 서버가 닫으면 끊김을 내고 끝난다`() = runTest {
        val events = collect {
            sockets[0].server("""{"goAway":{"timeLeft":"1.5s"}}""")
            sockets[0].serverClose(1000, "bye")
        }

        assertEquals(listOf(LiveEvent.GoAway(1.5.seconds), LiveEvent.Disconnected(1000, "bye")), events)
        assertEquals(1, sockets.size)
    }

    @Test
    fun `서버가 닫으면 코드와 이유를 끊김으로 낸다`() = runTest {
        val events = collect { sockets[0].serverClose(1011, "RESOURCE_EXHAUSTED") }

        assertEquals(listOf<LiveEvent>(LiveEvent.Disconnected(1011, "RESOURCE_EXHAUSTED")), events)
    }

    @Test
    fun `2시간 안에 다시 연결하면 받아 둔 핸들로 이어 붙인다`() = runTest {
        collect {
            sockets[0].server("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""")
            sockets[0].serverClose(1006, "")
        }
        clock.now += 119.minutes

        val events = collect { sockets[1].server("""{"setupComplete":{}}""") }

        assertEquals(listOf<LiveEvent>(LiveEvent.Resumed), events)
    }

    @Test
    fun `핸들을 받은 지 2시간이 지났으면 만료를 알리고 핸들 없이 새로 연다`() = runTest {
        collect {
            sockets[0].server("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""")
            sockets[0].serverClose(1006, "")
        }
        clock.now += 2.hours + 1.seconds

        val events = collect { sockets[1].server("""{"setupComplete":{}}""") }

        assertEquals(listOf(LiveEvent.ResumeExpired, LiveEvent.Connected), events)
        assertNull(sockets[1].sentJson(0)["setup"]!!.jsonObject["sessionResumption"]!!.jsonObject["handle"])
    }

    @Test
    fun `바깥에서 넣은 핸들로 첫 연결부터 이어 붙인다`() = runTest {
        val events = collect(resumeHandle = "h0", resumeHandleAt = clock.now - 1.hours) { sockets[0].server("""{"setupComplete":{}}""") }

        assertEquals(listOf<LiveEvent>(LiveEvent.Resumed), events)
        assertEquals("h0", sockets[0].sentJson(0)["setup"]!!.jsonObject["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `바깥에서 넣은 핸들을 받은 지 2시간이 지났으면 만료를 알리고 새로 연다`() = runTest {
        val events = collect(resumeHandle = "h0", resumeHandleAt = clock.now - 2.hours - 1.seconds) { sockets[0].server("""{"setupComplete":{}}""") }

        assertEquals(listOf(LiveEvent.ResumeExpired, LiveEvent.Connected), events)
    }

    @Test
    fun `연결을 열지 못하면 실패를 낸다`() = runTest {
        val failing = LiveSession(open = { throw LiveConnectException(429, "Too Many Requests") }, clock = clock)

        val events = failing.connect("test-key", config).toList()

        assertEquals(listOf<LiveEvent>(LiveEvent.Failed(429, "Too Many Requests")), events)
    }

    @Test
    fun `받다가 망이 끊겨 예외가 나면 키를 지운 이유로 끊김을 내고 끝난다`() = runTest {
        val events = collect { sockets[0].serverFail(IllegalStateException("closed wss://host/ws?key=test-key")) }

        assertEquals(listOf<LiveEvent>(LiveEvent.Disconnected(null, "closed wss://host/ws")), events)
    }

    @Test
    fun `열지 못한 이유에서 키를 지운다`() = runTest {
        val failing = LiveSession(open = { throw LiveConnectException(null, "failed wss://host/ws?key=test-key&alt=1") }, clock = clock)

        val events = failing.connect("test-key", config).toList()

        assertEquals(listOf<LiveEvent>(LiveEvent.Failed(null, "failed wss://host/ws&alt=1")), events)
    }

    @Test
    fun `보내다 예외가 나도 던지지 않고 버린다`() = runTest {
        collect {
            sockets[0].server("""{"setupComplete":{}}""")
            sockets[0].sendError = IllegalStateException("broken pipe")

            session.sendAudio(byteArrayOf(1))
        }
    }

    @Test
    fun `읽지 못하는 메시지 하나는 버리고 다음 메시지를 읽는다`() = runTest {
        val events = collect {
            sockets[0].server("""{"usageMetadata":{"promptTokensDetails":[{"tokenCount":1}]}}""")
            sockets[0].server("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"not base64!"}}]}}}""")
            sockets[0].server("""{"serverContent":{"turnComplete":true}}""")
        }

        assertEquals(listOf<LiveEvent>(LiveEvent.TurnComplete), events)
    }

    @Test
    fun `모으기를 멈추면 연결을 닫는다`() = runTest {
        collect { }

        assertTrue(sockets.single().closedByClient)
    }
}

private class FakeSocket : LiveSocket {
    val sent = mutableListOf<String>()
    var closedByClient = false
    var sendError: Exception? = null
    private val frames = Channel<String>(Channel.UNLIMITED)
    override var closeReason: LiveEvent.Disconnected? = null

    fun server(text: String) {
        frames.trySend(text)
    }

    fun serverFail(error: Exception) {
        frames.close(error)
    }

    fun serverClose(code: Int, reason: String) {
        closeReason = LiveEvent.Disconnected(code, reason)
        frames.close()
    }

    override suspend fun send(text: String) {
        sendError?.let { throw it }
        sent += text
    }

    /** 원인 없이 닫히면 null, 원인을 담아 닫히면 그 예외를 던진다. */
    override suspend fun receive(): String? = frames.receiveCatching().let { result -> result.exceptionOrNull()?.let { throw it } ?: result.getOrNull() }

    override suspend fun close() {
        closedByClient = true
        frames.close()
    }
}
