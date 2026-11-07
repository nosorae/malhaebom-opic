package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 코치 음성을 재생하는 동안에도 마이크를 연다. 사용자가 코치 말 도중에 끼어들 수 있게 하고(F11.4),
 * 스피커로 나간 코치 말이 마이크로 다시 들어오지 않게 플랫폼 에코 제거를 켠다.
 */
public interface DuplexAudio {
    /**
     * 모으는 동안 마이크와 스피커를 열고 [MIC_SAMPLE_RATE] 16bit 모노 리틀 엔디언 PCM 을 [MIC_CHUNK_BYTES] 조각으로 낸다.
     * 모으기를 멈추면 둘 다 닫는다. 마이크를 열지 못하면 [IllegalStateException] 으로 끝난다.
     */
    public val mic: Flow<MicChunk>

    /** [PLAYBACK_SAMPLE_RATE] 16bit 모노 리틀 엔디언 PCM 을 재생 대기열 끝에 넣는다. [mic] 을 모으는 중이 아니면 버린다. */
    public fun play(pcm: ByteArray)

    /** 재생 중인 소리와 대기열을 버린다. 사용자가 코치 말 도중에 말하면 부른다(F11.4). */
    public fun flush()

    /** 내 목소리 크기(0~1). 목소리 띠에 쓴다. 마이크가 닫혀 있으면 0 이다. */
    public val micLevel: StateFlow<Float>

    /** 재생 중인 코치 목소리 크기(0~1). 재생할 것이 없으면 0 이다. */
    public val playbackLevel: StateFlow<Float>

    public companion object {
        public const val MIC_SAMPLE_RATE: Int = 16_000
        public const val PLAYBACK_SAMPLE_RATE: Int = 24_000

        /**
         * 마이크 조각 40ms. 20ms 면 초당 보낼 메시지가 50개라 연결 부담이 크고,
         * 100ms 면 끼어든 말을 알아채 코치를 멈추기까지 그만큼 늦는다.
         */
        public const val MIC_CHUNK_BYTES: Int = MIC_SAMPLE_RATE * 2 * 40 / 1_000
    }
}

/** 마이크 조각 하나. [speech] 가 false 면 생각하는 동안의 침묵이라 보내지 않는다. */
public class MicChunk(public val pcm: ByteArray) {
    private val peak = peakOf(pcm)

    /** 조각의 가장 큰 소리를 귀에 들리는 크기에 맞춰 0~1 로 바꾼 값이다. */
    public val level: Float = loudness(peak)

    /** 가장 큰 소리가 말소리 기준(F5.5, -40dB)에 닿았다. */
    public val speech: Boolean = isSpeech(peak)
}
