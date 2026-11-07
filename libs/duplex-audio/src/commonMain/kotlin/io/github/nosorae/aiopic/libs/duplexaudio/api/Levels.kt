package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlin.math.abs
import kotlin.math.log10

/** 이보다 작은 소리는 음량 0 으로 친다. 녹음 화면의 목소리 띠(libs/recorder)와 같은 값이라 두 띠가 같게 움직인다. */
private const val FLOOR_DB = -50f

/** 말소리로 치는 가장 작은 소리. 답변 무음 판정(F5.5, engine/answer 의 음량 0.2)과 같은 기준이다. */
private const val SPEECH_DB = -40f

/** 16bit 리틀 엔디언 PCM 의 [from]~[to] 바이트 구간에서 가장 큰 진폭(0~1). */
internal fun peakOf(pcm: ByteArray, from: Int = 0, to: Int = pcm.size): Float {
    var max = 0
    for (i in from until to - 1 step 2) {
        val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
        max = maxOf(max, abs(sample))
    }
    return (max / 32_768f).coerceAtMost(1f)
}

/** 최대 진폭을 [FLOOR_DB]~0dB 구간의 0~1 로 바꾼다. 귀는 소리 크기를 로그로 듣는다. */
internal fun loudness(peak: Float): Float {
    if (peak <= 0f) return 0f
    return ((20 * log10(peak) - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
}

internal fun isSpeech(peak: Float): Boolean = peak > 0f && 20 * log10(peak) >= SPEECH_DB

/** 들어오는 PCM 을 [size] 바이트 조각으로 자른다. 모자란 끝은 다음 [push] 앞에 붙인다. */
internal class PcmChunker(private val size: Int) {
    private var rest = ByteArray(0)

    fun push(bytes: ByteArray): List<ByteArray> {
        val all = rest + bytes
        val full = all.size / size * size
        rest = all.copyOfRange(full, all.size)
        return (0 until full step size).map { all.copyOfRange(it, it + size) }
    }
}
