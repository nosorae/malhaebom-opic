package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.coach.LiveCoachTransport
import io.github.nosorae.aiopic.engine.coach.TransportEvent
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio
import io.github.nosorae.aiopic.libs.duplexaudio.api.MicChunk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/** 플랫폼마다 메모리 DB 를 만든다. [context] 에 테스트 디스패처를 주면 쿼리가 테스트 시계 위에서 돈다. */
expect fun inMemoryDatabase(context: CoroutineContext? = null): OpicDatabase

/** 연결마다 받은 핸들을 남기고, [server] 로 넣은 일을 지금 연결에 낸다. 끊김을 내면 흐름이 끝난다. */
class FakeTransport : LiveCoachTransport {
    val connects = mutableListOf<String?>()
    private var events = Channel<TransportEvent>(Channel.UNLIMITED)

    fun server(event: TransportEvent) {
        events.trySend(event)
    }

    override fun connect(instruction: String, tools: List<JsonObject>, resumeHandle: String?): Flow<TransportEvent> = flow {
        connects += resumeHandle
        events = Channel(Channel.UNLIMITED)
        for (event in events) {
            emit(event)
            if (event is TransportEvent.Disconnected) break
        }
    }

    override suspend fun sendAudio(pcm: ByteArray) = Unit

    override suspend fun sendAudioEnd() = Unit

    override suspend fun sendToolResponse(id: String, name: String, result: JsonElement) = Unit
}

/** 마이크는 아무것도 내지 않고, 목소리 크기는 테스트가 바꾼다. */
class FakeAudio : DuplexAudio {
    override val mic: Flow<MicChunk> = Channel<ByteArray>().receiveAsFlow().map(::MicChunk)
    override val micLevel = MutableStateFlow(0f)
    override val playbackLevel = MutableStateFlow(0f)

    override fun play(pcm: ByteArray) = Unit

    override fun flush() = Unit
}

class FakeLiveConsent(var given: Boolean = false) : LiveConsent {
    override suspend fun isLiveGiven() = given

    override suspend fun giveLive() {
        given = true
    }
}
