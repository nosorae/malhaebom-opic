package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 기기 안 말소리 감지(F20.3). 성대가 떠는 소리는 말소리이고, 크기만 큰 물소리 같은 잡음은 말소리가 아니다. */
class VoicedTest {
    @Test
    fun `F20_3 목소리 높이의 주기가 있는 소리는 말소리다`() {
        assertTrue(MicChunk(tone(hz = 180.0, amplitude = 8_000)).voiced)
    }

    @Test
    fun `F20_3 크기가 커도 주기가 없는 잡음은 말소리가 아니다`() {
        val random = Random(7)
        val noise = pcmOf(IntArray(DuplexAudio.MIC_CHUNK_BYTES / 2) { random.nextInt(-8_000, 8_000) })

        assertTrue(MicChunk(noise).speech)
        assertFalse(MicChunk(noise).voiced)
    }

    @Test
    fun `F20_3 한쪽으로 쏠리기만 한 소리는 말소리가 아니다`() {
        assertFalse(MicChunk(pcm(8_000)).voiced)
    }

    @Test
    fun `F20_3 주기가 있어도 말소리 기준보다 작으면 말소리가 아니다`() {
        assertFalse(MicChunk(tone(hz = 180.0, amplitude = 200)).voiced)
    }
}

/** [hz] 사인파 40ms. */
internal fun tone(hz: Double, amplitude: Int): ByteArray = pcmOf(IntArray(DuplexAudio.MIC_CHUNK_BYTES / 2) { (amplitude * sin(2 * PI * hz * it / DuplexAudio.MIC_SAMPLE_RATE)).toInt() })

internal fun pcmOf(samples: IntArray): ByteArray = ByteArray(samples.size * 2) { if (it % 2 == 0) samples[it / 2].toByte() else (samples[it / 2] shr 8).toByte() }
