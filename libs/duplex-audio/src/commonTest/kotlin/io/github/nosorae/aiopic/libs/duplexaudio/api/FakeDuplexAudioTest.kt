package io.github.nosorae.aiopic.libs.duplexaudio.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeDuplexAudioTest {
    @Test
    fun `F11_4 끼어들면 재생 대기열을 비운다`() {
        val audio = FakeDuplexAudio()
        audio.play(pcm(1_000))

        audio.flush()

        assertEquals(emptyList(), audio.queued)
        assertEquals(0f, audio.playbackLevel.value)
    }

    @Test
    fun `마이크에 말하면 말소리 조각이 나오고 내 음량이 오른다`() = runTest {
        val audio = FakeDuplexAudio()
        audio.say(pcm(3_000))

        val chunk = audio.mic.first()

        assertTrue(chunk.speech)
        assertEquals(chunk.level, audio.micLevel.value)
    }
}
