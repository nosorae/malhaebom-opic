package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

/** 테스트용. [say] 로 넣은 PCM 을 마이크 조각으로 내고, 재생은 [queued] 에 쌓기만 한다. */
class FakeDuplexAudio : DuplexAudio {
    private val spoken = Channel<ByteArray>(Channel.UNLIMITED)

    /** 재생 대기열. [flush] 하면 빈다. */
    val queued = mutableListOf<ByteArray>()

    override val micLevel = MutableStateFlow(0f)
    override val playbackLevel = MutableStateFlow(0f)
    override val mic: Flow<MicChunk> = spoken.receiveAsFlow().map(::MicChunk).onEach { micLevel.value = it.level }

    fun say(pcm: ByteArray) {
        spoken.trySend(pcm)
    }

    override fun play(pcm: ByteArray) {
        queued += pcm
        playbackLevel.value = MicChunk(pcm).level
    }

    override fun flush() {
        queued.clear()
        playbackLevel.value = 0f
    }
}
