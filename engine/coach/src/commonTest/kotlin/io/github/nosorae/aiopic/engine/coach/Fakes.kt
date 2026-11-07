package io.github.nosorae.aiopic.engine.coach

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.libs.duplexaudio.api.MicChunk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/** 플랫폼마다 메모리 DB 를 만든다. [context] 에 테스트 디스패처를 주면 쿼리가 테스트 시계 위에서 돈다. */
expect fun inMemoryDatabase(context: CoroutineContext? = null): OpicDatabase

/** 연결마다 받은 지시와 핸들을 남기고, [server] 로 넣은 일을 지금 연결에 낸다. 끊김을 내면 흐름이 끝난다. */
class FakeTransport : LiveCoachTransport {
    data class Connect(val instruction: String, val tools: List<JsonObject>, val resumeHandle: String?)

    val connects = mutableListOf<Connect>()
    val audio = mutableListOf<ByteArray>()
    var audioEnds = 0
    val responses = mutableListOf<Pair<String, JsonElement>>()

    /** 있으면 연결이 이 예외로 끝난다. */
    var connectError: Exception? = null
    private var events = Channel<TransportEvent>(Channel.UNLIMITED)

    fun server(event: TransportEvent) {
        events.trySend(event)
    }

    override fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<TransportEvent> = flow {
        connects += Connect(instruction, tools, resumeHandle)
        events = Channel(Channel.UNLIMITED)
        connectError?.let { throw it }
        for (event in events) {
            emit(event)
            if (event is TransportEvent.Disconnected) break
        }
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        audio += pcm
    }

    override suspend fun sendAudioEnd() {
        audioEnds++
    }

    override suspend fun sendToolResponse(id: String, name: String, result: JsonElement) {
        responses += id to result
    }
}

/** [say] 로 넣은 PCM 을 마이크 조각으로 내고 재생은 [queued] 에 쌓는다. */
class FakeAudio : DuplexAudio {
    private val spoken = Channel<ByteArray>(Channel.UNLIMITED)
    val queued = mutableListOf<ByteArray>()

    /** 있으면 마이크를 열지 못하고 이 예외로 끝난다. */
    var micError: Exception? = null

    override val mic: Flow<MicChunk> = flow {
        micError?.let { throw it }
        emitAll(spoken.receiveAsFlow().map(::MicChunk))
    }
    override val micLevel = MutableStateFlow(0f)
    override val playbackLevel = MutableStateFlow(0f)

    fun say(pcm: ByteArray) {
        spoken.trySend(pcm)
    }

    override fun play(pcm: ByteArray) {
        queued += pcm
    }

    override fun flush() = queued.clear()
}

/** 실제 마이크처럼 40ms 길이의, 모든 표본이 최대값인 조각과 무음 조각. */
val LOUD = ByteArray(DuplexAudio.MIC_CHUNK_BYTES) { if (it % 2 == 0) 0xFF.toByte() else 0x7F }
val SILENT = ByteArray(DuplexAudio.MIC_CHUNK_BYTES)
