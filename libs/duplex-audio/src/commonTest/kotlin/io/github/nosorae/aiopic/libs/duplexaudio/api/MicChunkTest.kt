package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicChunkTest {
    @Test
    fun `F5_5 무음 조각은 말소리가 아니다`() {
        val chunk = MicChunk(pcm(0))

        assertFalse(chunk.speech)
        assertEquals(0f, chunk.level)
    }

    @Test
    fun `F5_5 가장 큰 소리가 -40dB 보다 크면 말소리다`() {
        // 400 / 32768 은 약 -38dB 다.
        assertTrue(MicChunk(pcm(400)).speech)
    }

    @Test
    fun `F5_5 가장 큰 소리가 -40dB 보다 작으면 말소리가 아니다`() {
        // 300 / 32768 은 약 -41dB 다. 조용한 방 잡음이 이 근처다.
        assertFalse(MicChunk(pcm(300)).speech)
    }

    @Test
    fun `음수 표본도 크기로 치고 리틀 엔디언으로 읽는다`() {
        // -400 을 빅 엔디언으로 잘못 읽으면 아주 작은 값이 된다.
        assertTrue(MicChunk(pcm(-400)).speech)
    }

    @Test
    fun `음량은 0과 1 사이이고 가장 큰 소리는 1 이다`() {
        assertEquals(1f, MicChunk(pcm(Short.MIN_VALUE.toInt())).level)
        listOf(10, 300, 3_000, 30_000).forEach { assertTrue(MicChunk(pcm(it)).level in 0f..1f, "$it") }
    }
}

/** 16bit 리틀 엔디언 모노 PCM 40ms. 표본은 모두 [sample] 이다. */
internal fun pcm(sample: Int, bytes: Int = DuplexAudio.MIC_CHUNK_BYTES): ByteArray = ByteArray(bytes) { if (it % 2 == 0) sample.toByte() else (sample shr 8).toByte() }
