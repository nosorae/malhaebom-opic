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
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
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

    override val mic: Flow<MicChunk> = callbackFlow {
        // 탭 버퍼 길이는 하드웨어가 정해서 100ms 를 넘기도 한다. 정해진 조각 길이로 다시 자른다.
        val chunker = PcmChunker(MIC_CHUNK_BYTES)
        val onPcm: (ByteArray) -> Unit = { pcm ->
            chunker.push(pcm).forEach {
                val chunk = MicChunk(it)
                mutableMicLevel.value = chunk.level
                trySend(chunk)
            }
        }
        if (!start(onPcm)) {
            stop()
            throw IllegalStateException("마이크를 열 수 없다")
        }
        // 전화가 끝나거나 이어폰이 바뀌면 엔진이 멈춰 있다. 입력 형식이 바뀌었을 수 있어 탭부터 다시 연다.
        val reopener = Reopener(engine, running = { engine.running }) {
            stop()
            start(onPcm)
        }
        awaitClose {
            reopener.remove()
            stop()
            // 멈춰 있던 다른 앱의 소리가 다시 나게 세션을 놓는다. 앱이 끝에 내는 효과음이 다 나간 뒤에 놓는다.
            // 재생 중에 놓으면 실패해 세션이 켜진 채 남고, 놓은 뒤 효과음을 틀면 세션이 다시 켜진다. 그 사이 마이크를 다시 열었으면 놓지 않는다.
            // ponytail: 효과음 길이를 모르고 넉넉한 시간을 기다린다. 끝 효과음이 이보다 길어지면 같이 늘린다.
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, RELEASE_DELAY_NS), dispatch_get_main_queue()) {
                if (!open) AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
            }
        }
    }.buffer(Channel.UNLIMITED)

    override val interruptions: Flow<AudioInterruption> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            center.addObserverForName(AVAudioSessionInterruptionNotification, null, null) { note ->
                when (note.number(AVAudioSessionInterruptionTypeKey)) {
                    AVAudioSessionInterruptionTypeBegan -> trySend(AudioInterruption.Began)
                    AVAudioSessionInterruptionTypeEnded -> trySend(AudioInterruption.Ended)
                }
            },
            // 이어폰을 빼면 소리가 스피커로 나가기 전에 멈춘다. 다시 끼워도 저절로 다시 시작하지 않는다.
            center.addObserverForName(AVAudioSessionRouteChangeNotification, null, null) { note ->
                if (note.number(AVAudioSessionRouteChangeReasonKey) == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) trySend(AudioInterruption.Unplugged)
            },
        )
        awaitClose { observers.forEach(center::removeObserver) }
    }

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

    private companion object {
        /** 연습을 끝낸 뒤 세션을 놓기까지 기다리는 시간. 끝 효과음(0.5초 남짓)보다 넉넉하다. */
        const val RELEASE_DELAY_NS = 1_000_000_000L
    }
}

/**
 * 마이크가 멈췄을 때 다시 연다(F16.14). 끼어듦이 끝나거나 엔진 설정이 바뀌었는데 엔진이 멈춰 있으면 [reopen] 을 부른다.
 * 끼어듦 동안에는 설정이 바뀌어도 열지 않는다. 전화가 오면 경로가 수화기로 바뀌어 그 알림이 오지만 세션을 켤 수 없다.
 * 열지 못해도 흐름을 닫지 않는다. 엔진이 멈춘 채라 다음 끝이나 설정 바뀜 알림에서 또 연다.
 * 엔진이 돌고 있으면 열지 않아 다시 열면서 생긴 설정 바뀜 알림으로 또 여는 일을 막는다.
 */
internal class Reopener(engine: Any?, private val running: () -> Boolean, private val reopen: () -> Unit) {
    private val center = NSNotificationCenter.defaultCenter
    private var interrupted = false
    private val observers = listOf(
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, null) { note ->
            when (note.number(AVAudioSessionInterruptionTypeKey)) {
                AVAudioSessionInterruptionTypeBegan -> interrupted = true

                AVAudioSessionInterruptionTypeEnded -> {
                    interrupted = false
                    retry()
                }
            }
        },
        center.addObserverForName(AVAudioEngineConfigurationChangeNotification, engine, null) { if (!interrupted) retry() },
    )

    private fun retry() {
        if (!running()) reopen()
    }

    fun remove() = observers.forEach(center::removeObserver)
}

private fun NSNotification?.number(key: String?): ULong? = (this?.userInfo?.get(key) as? NSNumber)?.unsignedLongLongValue
