package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

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

/** 목소리 높이로 치는 범위(70~400Hz)를 [DuplexAudio.MIC_SAMPLE_RATE] 표본 간격으로 바꾼 값. */
private const val MIN_LAG = DuplexAudio.MIC_SAMPLE_RATE / 400
private const val MAX_LAG = DuplexAudio.MIC_SAMPLE_RATE / 70

/**
 * 조각이 목소리 높이만큼 밀린 자기와 이만큼 닮으면 성대가 떠는 소리로 본다(F20.3).
 * ponytail: 실기기에서 물소리가 넘거나 말소리가 못 넘으면 이 값만 고친다.
 */
internal const val VOICED_SIMILARITY = 0.5f

/**
 * 16bit PCM 이 목소리 높이 범위에서 주기를 가진 정도(0~1). 모음처럼 성대가 떠는 소리는 1 에 가깝고, 물소리 같은 잡음은 0 에 가깝다.
 * 평균을 빼고 재므로 한쪽으로 쏠리기만 한 소리는 0 이다.
 * ponytail: 음악처럼 주기가 있는 소리도 넘는다. 샤워 중 음악이 문제가 되면 학습된 감지 모델로 바꾼다.
 */
internal fun periodicity(pcm: ByteArray): Float {
    val x = FloatArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toFloat() }
    val mean = x.average().toFloat()
    for (i in x.indices) x[i] -= mean
    var best = 0f
    for (lag in MIN_LAG..minOf(MAX_LAG, x.size / 2)) {
        var cross = 0f
        var head = 0f
        var tail = 0f
        for (i in 0 until x.size - lag) {
            cross += x[i] * x[i + lag]
            head += x[i] * x[i]
            tail += x[i + lag] * x[i + lag]
        }
        if (head > 0f && tail > 0f) best = maxOf(best, cross / sqrt(head * tail))
    }
    return best
}

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
