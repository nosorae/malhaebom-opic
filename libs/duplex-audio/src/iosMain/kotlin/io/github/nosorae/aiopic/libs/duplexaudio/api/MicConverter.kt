package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16

/**
 * 하드웨어 표본율의 마이크 버퍼를 [DuplexAudio.MIC_SAMPLE_RATE] 16bit 모노 PCM 바이트로 바꾼다.
 * 변환기를 버퍼마다 새로 만들지 않고 이어 써서 버퍼 경계에서 소리가 튀지 않는다.
 */
@OptIn(ExperimentalForeignApi::class)
internal class MicConverter(from: AVAudioFormat) {
    private val to = AVAudioFormat(
        commonFormat = AVAudioPCMFormatInt16,
        sampleRate = DuplexAudio.MIC_SAMPLE_RATE.toDouble(),
        channels = 1u,
        interleaved = true,
    )
    private val converter = AVAudioConverter(fromFormat = from, toFormat = to)

    fun convert(buffer: AVAudioPCMBuffer): ByteArray {
        // 변환기 안에 남은 표본이 함께 나올 수 있어 조금 넉넉히 잡는다.
        val capacity = (buffer.frameLength.toDouble() * to.sampleRate / buffer.format.sampleRate).toUInt() + 64u
        val out = AVAudioPCMBuffer(to, frameCapacity = capacity)
        var given = false
        // 버퍼 하나를 넘기고 나면 "지금은 더 없다"고 답한다. 끝났다고 답하면 변환기가 다음 버퍼를 받지 않는다.
        converter.convertToBuffer(out, error = null) { _, status ->
            status?.pointed?.value = if (given) AVAudioConverterInputStatus_NoDataNow else AVAudioConverterInputStatus_HaveData
            buffer.takeUnless { given }.also { given = true }
        }
        return out.int16ChannelData!![0]!!.reinterpret<ByteVar>().readBytes(out.frameLength.toInt() * 2)
    }
}
