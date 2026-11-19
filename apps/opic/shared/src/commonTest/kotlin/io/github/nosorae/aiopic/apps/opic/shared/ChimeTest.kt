package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 상태가 바뀔 때 다섯 가지 효과음 가운데 하나를 고른다(F16.19). */
class ChimeTest {
    private val at = Instant.parse("2026-11-20T10:00:00Z")
    private val listening = SpeakingState("p1", at, 20.minutes, AudioRoute.Earphones, "cooking")
    private val paused = listening.copy(status = SpeakingStatus.Paused, pausedAt = at)

    @Test
    fun `F16_19 시작 일시정지 다시 시작 표현 저장 끝에만 효과음을 낸다`() {
        val chimes = listOf(
            chime(null, listening),
            chime(listening, paused),
            chime(paused, listening),
            chime(listening, listening.copy(savedCount = 1)),
            chime(listening, listening.copy(finished = true)),
            chime(listening.copy(finished = true), listening.copy(id = "p2")),
            chime(listening, listening.copy(status = SpeakingStatus.CoachSpeaking)),
        )

        assertEquals(listOf(Chime.Start, Chime.Pause, Chime.Resume, Chime.Saved, Chime.End, Chime.Start, null), chimes)
    }

    @Test
    fun `F16_19 효과음 다섯은 서로 다른 소리다`() {
        val sounds = Chime.entries.map { it.pcm().toList() }

        assertTrue(sounds.none { it.isEmpty() } && sounds.toSet().size == Chime.entries.size)
    }
}
