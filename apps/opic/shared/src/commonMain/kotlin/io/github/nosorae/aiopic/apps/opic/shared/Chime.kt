package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import io.github.nosorae.aiopic.engine.speaking.SpeakingStatus
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 연습 상태가 바뀔 때 내는 효과음 다섯(F16.19). 화면을 보지 않는 사용자가 소리만으로 지금 상태를 안다.
 * 음 높이 [notes] 를 차례로 짧게 울린다. 올라가면 시작과 다시 시작, 내려가면 일시정지와 끝이고 높은 두 번은 표현 저장이다.
 */
enum class Chime(vararg val notes: Double) {
    Start(523.25, 659.25, 783.99),
    Pause(783.99, 523.25),
    Resume(523.25, 783.99),
    Saved(1046.5, 1046.5),
    End(783.99, 659.25, 523.25, 392.0),
    ;

    /** [SAMPLE_RATE] 16비트 모노 리틀 엔디언 PCM. 음마다 앞뒤를 짧게 줄여 딸깍 소리를 없앤다. */
    fun pcm(): ByteArray {
        val note = SAMPLE_RATE * NOTE_MS / 1_000
        val fade = SAMPLE_RATE * FADE_MS / 1_000
        val out = ByteArray(notes.size * note * 2)
        notes.forEachIndexed { index, frequency ->
            for (i in 0 until note) {
                val envelope = min(1.0, min(i, note - 1 - i).toDouble() / fade)
                val sample = (sin(2 * PI * frequency * i / SAMPLE_RATE) * envelope * VOLUME * Short.MAX_VALUE).toInt()
                val at = (index * note + i) * 2
                out[at] = sample.toByte()
                out[at + 1] = (sample shr 8).toByte()
            }
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val NOTE_MS = 110
        private const val FADE_MS = 8

        // ponytail: 코치 목소리보다 작게 둔다. 실기기에서 샤워 소리에 묻히면 이 값만 올린다.
        private const val VOLUME = 0.35
    }
}

/** 상태가 [before] 에서 [after] 로 바뀔 때 낼 효과음. 낼 것이 없으면 null 이다(F16.19). */
fun chime(before: SpeakingState?, after: SpeakingState?): Chime? {
    if (after == null) return null
    val paused = after.status == SpeakingStatus.Paused
    return when {
        before == null || before.id != after.id -> Chime.Start
        after.finished -> if (before.finished) null else Chime.End
        !paused && before.status == SpeakingStatus.Paused -> Chime.Resume
        paused && before.status != SpeakingStatus.Paused -> Chime.Pause
        after.savedCount > before.savedCount -> Chime.Saved
        else -> null
    }
}
