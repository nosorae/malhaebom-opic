package io.github.nosorae.aiopic.engine.speaking

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.libs.duplexaudio.api.MicChunk
import io.github.nosorae.aiopic.libs.livegemini.LiveEvent
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

/** 가짜 연결. 연결마다 받은 지시와 핸들을 남기고, [server] 로 넣은 일을 지금 연결에 낸다. 끊김이나 실패를 내면 흐름이 끝난다. */
class FakeLink : SpeakingLink {
    data class Connect(val instruction: String, val tools: List<JsonObject>, val resumeHandle: String?)

    val connects = mutableListOf<Connect>()
    val audio = mutableListOf<ByteArray>()
    var audioEnds = 0
    val texts = mutableListOf<String>()
    val responses = mutableListOf<Pair<String, JsonElement>>()
    private var events = Channel<LiveEvent>(Channel.UNLIMITED)

    fun server(vararg event: LiveEvent) = event.forEach { events.trySend(it) }

    override fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<LiveEvent> = flow {
        connects += Connect(instruction, tools, resumeHandle)
        events = Channel(Channel.UNLIMITED)
        for (event in events) {
            emit(event)
            if (event is LiveEvent.Disconnected || event is LiveEvent.Failed) break
        }
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        audio += pcm
    }

    override suspend fun sendAudioEnd() {
        audioEnds++
    }

    override suspend fun sendText(text: String) {
        texts += text
    }

    override suspend fun sendToolResponse(id: String, name: String, result: JsonElement) {
        responses += id to result
    }
}

/** [say] 로 넣은 PCM 을 마이크 조각으로 내고 재생은 [queued] 에 쌓는다. */
class FakeAudio : DuplexAudio {
    private val spoken = Channel<ByteArray>(Channel.UNLIMITED)
    val queued = mutableListOf<ByteArray>()

    override val mic: Flow<MicChunk> = flow { emitAll(spoken.receiveAsFlow().map(::MicChunk)) }
    override val micLevel = MutableStateFlow(0f)
    override val playbackLevel = MutableStateFlow(0f)

    fun say(vararg pcm: ByteArray) = pcm.forEach { spoken.trySend(it) }

    override fun play(pcm: ByteArray) {
        queued += pcm
    }

    override fun flush() = queued.clear()
}

/** 실제 마이크처럼 40ms 길이의, 모든 표본이 최대값인 조각과 무음 조각. */
val LOUD = ByteArray(DuplexAudio.MIC_CHUNK_BYTES) { if (it % 2 == 0) 0xFF.toByte() else 0x7F }
val SILENT = ByteArray(DuplexAudio.MIC_CHUNK_BYTES)

/** 코치 음성 1초(24kHz 16비트 모노). */
val COACH_SECOND = ByteArray(DuplexAudio.PLAYBACK_SAMPLE_RATE * 2)
