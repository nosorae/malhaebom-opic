package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class MicConverterTest {
    @Test
    fun `48kHz 마이크 소리를 16kHz 16비트 PCM 으로 바꾼다`() {
        val format = AVAudioFormat(standardFormatWithSampleRate = 48_000.0, channels = 1u)
        val converter = MicConverter(format)

        // 0.1초 버퍼 열 개. 진폭 0.5 는 약 -6dB 라 말소리다.
        val pcm = (1..10).map { converter.convert(buffer(format, sample = 0.5f)) }.reduce(ByteArray::plus)

        // 1초는 32000 바이트다. 변환기가 앞부분을 조금 붙잡고 있어 약간 모자랄 수 있다.
        assertTrue(pcm.size in 30_000..32_000, "${pcm.size}")
        val chunk = MicChunk(pcm)
        assertTrue(chunk.speech)
        assertTrue(chunk.level in 0.8f..0.9f, "${chunk.level}")
    }

    private fun buffer(format: AVAudioFormat, sample: Float) = AVAudioPCMBuffer(format, frameCapacity = 4_800u).apply {
        frameLength = 4_800u
        val samples = floatChannelData!![0]!!
        repeat(4_800) { samples[it] = sample }
    }
}
