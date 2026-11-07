package io.github.nosorae.aiopic.libs.duplexaudio.api

import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio.Companion.MIC_CHUNK_BYTES
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio.Companion.PLAYBACK_SAMPLE_RATE
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.setActive
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * `AVAudioEngine` 하나로 마이크와 재생을 함께 연다. 입력 노드의 음성 처리 모드를 켜면 엔진 출력이 에코 제거 기준이 되어
 * 스피커로 나간 코치 말이 마이크 소리에서 빠진다. 세션은 `.playAndRecord` 와 `.voiceChat` 모드이고 소리는 스피커로 낸다.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosDuplexAudio : DuplexAudio {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode().also(engine::attachNode)
    private val playFormat = AVAudioFormat(standardFormatWithSampleRate = PLAYBACK_SAMPLE_RATE.toDouble(), channels = 1u)

    @Volatile private var open = false
    private val mutableMicLevel = MutableStateFlow(0f)
    private val mutablePlaybackLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = mutableMicLevel
    override val playbackLevel: StateFlow<Float> = mutablePlaybackLevel

    // ponytail: 전화 같은 세션 끼어듦은 다루지 않는다. 라이브 코치 화면이 생기면 recorder 처럼 끼어듦 알림으로 다시 연다.
    override val mic: Flow<MicChunk> = callbackFlow {
        // 탭 버퍼 길이는 하드웨어가 정해서 100ms 를 넘기도 한다. 정해진 조각 길이로 다시 자른다.
        val chunker = PcmChunker(MIC_CHUNK_BYTES)
        val started = start { pcm ->
            chunker.push(pcm).forEach {
                val chunk = MicChunk(it)
                mutableMicLevel.value = chunk.level
                trySend(chunk)
            }
        }
        if (!started) {
            stop()
            throw IllegalStateException("마이크를 열 수 없다")
        }
        awaitClose(::stop)
    }.buffer(Channel.UNLIMITED)

    private fun start(onPcm: (ByteArray) -> Unit): Boolean {
        val session = AVAudioSession.sharedInstance()
        val ready = session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeVoiceChat,
            options = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        ) && session.setActive(true, error = null)
        if (!ready) return false
        val input = engine.inputNode
        // 엔진이 멈춘 동안 켜야 한다. 켜면 입력 형식이 바뀌니 형식은 그 뒤에 읽는다.
        if (!input.setVoiceProcessingEnabled(true, error = null)) return false
        val format = input.outputFormatForBus(0u)
        // 마이크 권한이 없거나 입력 장치가 없으면 표본율이 0 이다.
        if (format.sampleRate == 0.0) return false
        val converter = MicConverter(format)
        input.installTapOnBus(0u, bufferSize = 4_096u, format = format) { buffer, _ -> buffer?.let { onPcm(converter.convert(it)) } }
        engine.connect(player, to = engine.mainMixerNode, format = playFormat)
        // 재생 노드 출력에서 재니 실제로 나가는 소리의 크기다. 대기열이 비면 무음이라 0 이 된다.
        player.installTapOnBus(0u, bufferSize = 1_024u, format = null) { buffer, _ -> buffer?.let { mutablePlaybackLevel.value = loudness(peakOf(it)) } }
        engine.prepare()
        if (!engine.startAndReturnError(null)) return false
        player.play()
        open = true
        return true
    }

    private fun stop() {
        open = false
        engine.inputNode.removeTapOnBus(0u)
        player.removeTapOnBus(0u)
        player.stop()
        engine.stop()
        mutableMicLevel.value = 0f
        mutablePlaybackLevel.value = 0f
    }

    override fun play(pcm: ByteArray) {
        if (!open) return
        val frames = pcm.size / 2
        val buffer = AVAudioPCMBuffer(playFormat, frameCapacity = frames.toUInt())
        buffer.frameLength = frames.toUInt()
        val samples = buffer.floatChannelData!![0]!!
        for (i in 0 until frames) {
            val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            samples[i] = sample / 32_768f
        }
        player.scheduleBuffer(buffer, completionHandler = null)
    }

    /** 플레이어를 멈추면 예약한 버퍼가 모두 버려진다. 다음 [play] 를 바로 재생하게 다시 켠다. */
    override fun flush() {
        if (!open) return
        player.stop()
        player.play()
    }

    private fun peakOf(buffer: AVAudioPCMBuffer): Float {
        val samples = buffer.floatChannelData?.get(0) ?: return 0f
        var max = 0f
        for (i in 0 until buffer.frameLength.toInt()) max = maxOf(max, abs(samples[i]))
        return max
    }
}
