package io.github.nosorae.aiopic.libs.livegemini

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 연결 설정. [tools] 는 functionDeclarations 의 항목 하나하나를 그대로 넣는다.
 * 맥락 압축은 [compressionTriggerTokens] 에 닿으면 오래된 대화를 [compressionTargetTokens] 까지 덜어 낸다.
 * 목표를 시작점의 절반으로 둬서 한 번 덜어 내면 몇 턴 동안 다시 덜어 내지 않는다.
 */
public data class LiveConfig(
    val model: String,
    val systemInstruction: String,
    val voice: String,
    val tools: List<JsonObject> = emptyList(),
    val compressionTriggerTokens: Int = 12_000,
    val compressionTargetTokens: Int = 6_000,
)

/**
 * Gemini Live 연결 하나. [connect] 의 흐름을 모으는 동안 연결을 유지하고, GoAway 가 오면 이어 붙이기 핸들로 새 연결을 연다.
 * 한 번에 한 곳에서만 모은다.
 */
public class LiveSession(
    private val open: suspend (url: String) -> LiveSocket = ktorLiveSocket(),
    private val clock: Clock = Clock.System,
) {
    /** setupComplete 를 받은 연결. 받기 전과 다시 여는 동안은 null 이라 보내는 것을 버린다. */
    private val ready = MutableStateFlow<LiveSocket?>(null)
    private var handle: String? = null
    private var handleAt = Instant.DISTANT_PAST

    /**
     * 모으기를 멈추면 연결을 닫는다. 끝나는 이벤트는 [LiveEvent.Disconnected] 와 [LiveEvent.Failed] 다.
     * [resumeHandle] 은 앱이 저장해 둔 핸들이고 [resumeHandleAt] 은 그 핸들을 받은 시각이다. 앱이 죽었다 살아나도 대화를 이어 붙인다.
     */
    public fun connect(apiKey: String, config: LiveConfig, resumeHandle: String? = null, resumeHandleAt: Instant? = null): Flow<LiveEvent> = channelFlow {
        if (resumeHandle != null) {
            handle = resumeHandle
            handleAt = resumeHandleAt ?: clock.now()
        }
        while (true) {
            if (handle != null && clock.now() - handleAt > RESUME_LIMIT) {
                handle = null
                send(LiveEvent.ResumeExpired)
            }
            val socket = try {
                open(url(apiKey))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(LiveEvent.Failed((e as? LiveConnectException)?.status, hideKey(e.message)))
                return@channelFlow
            }
            val reconnect = try {
                socket.send(setup(config, handle).toString())
                read(socket, resuming = handle != null)
            } catch (e: Exception) {
                // 망이 끊기면 엔진이 받기 채널을 예외로 닫는다. 엔진에 따라 취소 예외로 오기도 해서, 모으는 쪽이 멈춘 것이 아니면 끊김으로 본다.
                ensureActive()
                send(LiveEvent.Disconnected(null, hideKey(e.message)))
                false
            } finally {
                ready.value = null
                // 모으기를 멈춰 취소된 뒤에도 닫기 프레임은 보내야 서버가 연결을 바로 놓는다.
                withContext(NonCancellable) { socket.close() }
            }
            if (!reconnect) return@channelFlow
        }
    }

    /** [pcm] 은 16kHz 모노 16비트 리틀엔디언이다. */
    public suspend fun sendAudio(pcm: ByteArray): Unit = sendRealtime {
        putJsonObject("audio") {
            put("data", Base64.encode(pcm))
            put("mimeType", "audio/pcm;rate=16000")
        }
    }

    /** 마이크를 잠깐 껐다. 서버 쪽 음성 감지가 쌓아 둔 오디오를 비운다. */
    public suspend fun sendAudioEnd(): Unit = sendRealtime { put("audioStreamEnd", true) }

    public suspend fun sendText(text: String): Unit = sendRealtime { put("text", text) }

    public suspend fun sendToolResponse(id: String, name: String, result: JsonElement) {
        sendJson(
            buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        addJsonObject {
                            put("id", id)
                            put("name", name)
                            putJsonObject("response") { put("result", result) }
                        }
                    }
                }
            },
        )
    }

    private suspend fun sendRealtime(body: JsonObjectBuilder.() -> Unit) = sendJson(buildJsonObject { putJsonObject("realtimeInput", body) })

    // ponytail: 연결 전과 다시 여는 1초 남짓 동안 보낸 것은 버린다. 도구 응답을 잃는 일이 생기면 그때 쌓아 뒀다 보낸다.
    // 끊긴 소켓에 보내다 난 예외도 같은 규칙으로 버린다. 끊김은 받는 쪽이 알린다.
    private suspend fun sendJson(message: JsonObject) {
        try {
            ready.value?.send(message.toString())
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }

    /** 연결이 끝날 때까지 읽는다. GoAway 뒤 이어 붙일 핸들이 있으면 true 를 돌려 새 연결을 열게 한다. */
    private suspend fun ProducerScope<LiveEvent>.read(socket: LiveSocket, resuming: Boolean): Boolean {
        while (true) {
            val text = socket.receive() ?: break
            // 모양이 다른 메시지 하나(빠진 필드, 깨진 base64)는 버리고 다음 메시지를 읽는다.
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val events = runCatching { events(message) }.getOrNull() ?: continue
            if ("setupComplete" in message) {
                ready.value = socket
                send(if (resuming) LiveEvent.Resumed else LiveEvent.Connected)
            }
            for (event in events) {
                if (event is LiveEvent.ResumeHandle) {
                    handle = event.handle
                    handleAt = clock.now()
                }
                send(event)
            }
            if (events.any { it is LiveEvent.GoAway } && handle != null) return true
        }
        send(socket.closeReason ?: LiveEvent.Disconnected(null, ""))
        return false
    }

    private fun setup(config: LiveConfig, handle: String?) = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/${config.model}")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voice) } } }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", config.systemInstruction) } } }
            if (config.tools.isNotEmpty()) putJsonArray("tools") { addJsonObject { put("functionDeclarations", JsonArray(config.tools)) } }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("contextWindowCompression") {
                put("triggerTokens", config.compressionTriggerTokens)
                putJsonObject("slidingWindow") { put("targetTokens", config.compressionTargetTokens) }
            }
            // 핸들 없이도 빈 설정을 보내야 서버가 이어 붙이기 핸들을 보내 준다.
            putJsonObject("sessionResumption") { handle?.let { put("handle", it) } }
        }
    }

    private companion object {
        /** 이어 붙이기 핸들은 끊긴 뒤 2시간 유효하다. 받은 시각부터 재서 조금 일찍 만료로 본다. */
        val RESUME_LIMIT = 2.hours

        // WebSocket 핸드셰이크에 x-goog-api-key 헤더가 받아지는지 확인하지 못해서 공식 방식인 쿼리로 보낸다.
        // 이 라이브러리는 주소를 어디에도 찍지 않는다.
        fun url(apiKey: String) = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
    }
}

/** 서버 메시지 하나를 바깥에 알릴 일로 바꾼다. 한 메시지에 여러 필드가 함께 온다. 모양이 다르면 예외를 던진다. */
private fun events(message: JsonObject): List<LiveEvent> = buildList {
    message["sessionResumptionUpdate"]?.jsonObject?.let { update ->
        val newHandle = update["newHandle"]?.jsonPrimitive?.contentOrNull
        // resumable 이 false 면 handle 이 비어 온다. 앞서 받은 핸들은 그대로 쓸 수 있어 지우지 않는다.
        if (update["resumable"]?.jsonPrimitive?.booleanOrNull == true && !newHandle.isNullOrEmpty()) add(LiveEvent.ResumeHandle(newHandle))
    }
    // 사용량을 턴 끝보다 먼저 낸다. 턴 끝에 연결을 닫는 쪽이 같은 메시지의 사용량을 잃지 않는다.
    message["usageMetadata"]?.jsonObject?.let { usage ->
        add(
            LiveEvent.Usage(
                promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                responseTokens = usage["responseTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                promptByModality = usage.byModality("promptTokensDetails"),
                responseByModality = usage.byModality("responseTokensDetails"),
            ),
        )
    }
    message["serverContent"]?.jsonObject?.let { content ->
        content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
            part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull?.let { add(LiveEvent.AudioChunk(Base64.decode(it))) }
        }
        content.text("inputTranscription")?.let { add(LiveEvent.InputTranscript(it)) }
        content.text("outputTranscription")?.let { add(LiveEvent.OutputTranscript(it)) }
        if (content.flag("interrupted")) add(LiveEvent.Interrupted)
        if (content.flag("turnComplete")) add(LiveEvent.TurnComplete)
    }
    message["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.forEach { call ->
        val c = call.jsonObject
        add(LiveEvent.ToolCall(c["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), c["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), c["args"]?.jsonObject ?: JsonObject(emptyMap())))
    }
    message["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray?.let { ids -> add(LiveEvent.ToolCallCancelled(ids.map { it.jsonPrimitive.content })) }
    message["goAway"]?.jsonObject?.let { add(LiveEvent.GoAway(parseDuration(it["timeLeft"]?.jsonPrimitive?.contentOrNull))) }
}

/** 엔진 예외 글에 연결 주소가 실려 올 수 있다(Darwin). 키가 화면이나 기록에 남지 않게 key 쿼리를 지운다. */
private fun hideKey(message: String?) = message.orEmpty().replace(Regex("""[?&]key=[^&\s'"]*"""), "")

private fun JsonObject.text(key: String) = get(key)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull == true

private fun JsonObject.byModality(key: String): Map<String, Int> = get(key)?.jsonArray.orEmpty()
    .associate { it.jsonObject["modality"]!!.jsonPrimitive.content to (it.jsonObject["tokenCount"]?.jsonPrimitive?.int ?: 0) }

/** 서버는 Duration 을 "50s", "1.5s" 처럼 초 단위 글로 준다. 읽지 못하면 0 이다. */
private fun parseDuration(value: String?): Duration = value?.removeSuffix("s")?.toDoubleOrNull()?.seconds ?: Duration.ZERO
