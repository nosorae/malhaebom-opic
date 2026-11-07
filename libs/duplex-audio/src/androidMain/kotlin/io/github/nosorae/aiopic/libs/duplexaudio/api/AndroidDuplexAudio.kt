package io.github.nosorae.aiopic.libs.duplexaudio.api

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio.Companion.MIC_CHUNK_BYTES
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio.Companion.MIC_SAMPLE_RATE
import io.github.nosorae.aiopic.libs.duplexaudio.api.DuplexAudio.Companion.PLAYBACK_SAMPLE_RATE
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 마이크는 `VOICE_COMMUNICATION` 입력으로 열고 [AcousticEchoCanceler] 가 있으면 켠다. 재생은 같은 통화 용도의 [AudioTrack] 이라
 * 플랫폼이 재생 소리를 에코 제거 기준으로 쓴다. 마이크와 재생은 각자 스레드에서 막히는 읽기, 쓰기로 돈다.
 */
public class AndroidDuplexAudio(context: Context) : DuplexAudio {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ponytail: 포커스는 잡고 놓기만 한다. 전화가 끼어들 때 멈추고 다시 여는 일은 라이브 코치 화면이 생기면 recorder 처럼 더한다.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).build()
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var open = false

    @Volatile private var flushing = false
    private val mutableMicLevel = MutableStateFlow(0f)
    private val mutablePlaybackLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = mutableMicLevel
    override val playbackLevel: StateFlow<Float> = mutablePlaybackLevel

    // 마이크 권한은 앱이 먼저 받는다. 없으면 AudioRecord 가 초기화되지 않아 아래에서 막힌다.
    @SuppressLint("MissingPermission")
    override val mic: Flow<MicChunk> = callbackFlow {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), MIC_CHUNK_BYTES * 4),
        )
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // 최소 크기의 두 배다. 최소 크기면 재생 스레드가 조금만 늦어도 소리가 끊긴다.
            .setBufferSizeInBytes(2 * AudioTrack.getMinBufferSize(PLAYBACK_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            .build()

        fun release() {
            echo?.release()
            record.release()
            track.release()
            queue.clear()
            audio.abandonAudioFocusRequest(focus)
            mutableMicLevel.value = 0f
            mutablePlaybackLevel.value = 0f
        }

        audio.requestAudioFocus(focus)
        if (record.state == AudioRecord.STATE_INITIALIZED) record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // 권한이 없거나 다른 앱이 마이크를 쥐고 있다.
            release()
            throw IllegalStateException("마이크를 열 수 없다")
        }
        track.play()
        open = true
        val reader = thread(name = "duplex-mic") {
            val buffer = ByteArray(MIC_CHUNK_BYTES)
            while (open) {
                // 막히는 읽기라 조각 하나가 다 차야 돌아온다. 멈출 때는 record.stop 이 읽기를 풀어 준다.
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (open) close(IllegalStateException("마이크를 읽지 못했다: $read"))
                    break
                }
                val chunk = MicChunk(buffer.copyOf(read))
                mutableMicLevel.value = chunk.level
                trySend(chunk)
            }
        }
        val player = thread(name = "duplex-play") { playLoop(track) }
        awaitClose {
            open = false
            record.stop()
            reader.join()
            // 대기열에서 기다리는 중이면 interrupt 가, 쓰는 중이면 track.stop 이 깨운다.
            player.interrupt()
            track.stop()
            player.join()
            release()
        }
    }.buffer(Channel.UNLIMITED)

    @Synchronized
    override fun play(pcm: ByteArray) {
        if (open) queue.put(pcm)
    }

    /** 재생 스레드만 [AudioTrack] 을 만지게 대기열을 비우고 표시를 넣는다. 쓰던 소리는 다음 조각 경계에서 멈춘다. */
    @Synchronized
    override fun flush() {
        if (!open) return
        queue.clear()
        flushing = true
        queue.put(FLUSH)
    }

    private fun playLoop(track: AudioTrack) {
        try {
            while (open) {
                val pcm = queue.take()
                if (pcm === FLUSH) {
                    // 버퍼에 써 둔 소리까지 버린다.
                    track.pause()
                    track.flush()
                    track.play()
                    flushing = false
                    mutablePlaybackLevel.value = 0f
                    continue
                }
                var at = 0
                while (at < pcm.size && open && !flushing) {
                    val end = minOf(at + SLICE_BYTES, pcm.size)
                    // ponytail: 버퍼에 쓰는 순간의 음량이라 실제로 들리는 것보다 버퍼 길이만큼 앞선다. 띠가 어긋나 보이면 재생 위치로 맞춘다.
                    mutablePlaybackLevel.value = loudness(peakOf(pcm, at, end))
                    track.write(pcm, at, end - at)
                    at = end
                }
                if (queue.isEmpty()) mutablePlaybackLevel.value = 0f
            }
        } catch (_: InterruptedException) {
            // 모으기가 끝났다.
        }
    }

    private companion object {
        val FLUSH = ByteArray(0)

        /** 재생을 20ms 씩 쓴다. 끼어들 때 이만큼 안에 멈추고, 띠 음량도 이 간격으로 바뀐다. */
        const val SLICE_BYTES = PLAYBACK_SAMPLE_RATE * 2 * 20 / 1_000
    }
}
