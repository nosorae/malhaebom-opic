package io.github.nosorae.aiopic.engine.speaking

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 스피커 모드에서 코치가 말하는 중에 이만큼 넘게 내 말소리가 이어지면 코치를 끊는다(F20.4).
 * 처음 값이고, 물소리를 튼 실기기에서 코치가 스스로 끊기지 않으면서 늦지 않게 끊기는 값으로 다시 잰다.
 */
val BARGE_IN_AFTER: Duration = 600.milliseconds

/** 말소리 사이 이만큼까지 끊긴 것은 이어진 것으로 본다. 's', 'f' 같은 떨리지 않는 자음이 말소리로 잡히지 않는 틈이다. */
val BARGE_IN_GAP: Duration = 200.milliseconds

/**
 * 코치가 말하는 중의 마이크 조각을 보내지 않고 붙잡는다(F20.4). 첫 말소리부터 [after] 넘게 말소리가 이어지면 붙잡은 조각을 모두 내놓고,
 * 그 전에 [gap] 넘게 끊기면 버린다. 물소리나 에코 제거 뒤에 남은 코치 목소리가 잠깐 말소리로 잡혀도 코치를 끊지 않는다.
 */
internal class BargeIn(private val after: Duration = BARGE_IN_AFTER, private val gap: Duration = BARGE_IN_GAP) {
    private val held = mutableListOf<ByteArray>()
    private var heard = Duration.ZERO
    private var quiet = Duration.ZERO

    /** 끊을 때면 붙잡은 조각을 순서대로 돌려주고 처음부터 다시 잰다. 아니면 null 이다. */
    fun hold(pcm: ByteArray, speech: Boolean, length: Duration): List<ByteArray>? {
        if (!speech) {
            if (held.isEmpty()) return null
            quiet += length
            if (quiet > gap) clear() else keep(pcm, length)
            return null
        }
        quiet = Duration.ZERO
        keep(pcm, length)
        if (heard <= after) return null
        return held.toList().also { clear() }
    }

    fun clear() {
        held.clear()
        heard = Duration.ZERO
        quiet = Duration.ZERO
    }

    private fun keep(pcm: ByteArray, length: Duration) {
        held += pcm
        heard += length
    }
}
