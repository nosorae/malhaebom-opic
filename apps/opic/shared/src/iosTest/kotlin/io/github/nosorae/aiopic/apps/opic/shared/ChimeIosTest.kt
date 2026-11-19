package io.github.nosorae.aiopic.apps.opic.shared

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/** 효과음 다섯을 iOS 플레이어가 열 수 있는 소리로 만든다(F16.19). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class ChimeIosTest {
    @Test
    fun `F16_19 효과음 다섯을 iOS 플레이어가 연다`() {
        val lengths = Chime.entries.map { chime ->
            val wav = wav(chime.pcm(), Chime.SAMPLE_RATE)
            val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
            (AVAudioPlayer(data = data, error = null).duration * 1_000).roundToInt()
        }

        assertEquals(Chime.entries.map { it.notes.size * 110 }, lengths)
    }
}
