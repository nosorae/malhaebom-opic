package io.github.nosorae.aiopic.engine.speaking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** 스피커 모드에서 코치가 말하는 중의 끊기 판정(F20.4). 40ms 조각 15개가 0.6초다. */
class BargeInTest {
    private val chunk = 40.milliseconds
    private val bargeIn = BargeIn()

    private fun feed(vararg speech: Boolean) = speech.map { bargeIn.hold(byteArrayOf(), it, chunk) }

    @Test
    fun `F20_4 말소리가 0점6초 넘게 이어지면 붙잡은 조각을 모두 내놓고 끊는다`() {
        val results = feed(*BooleanArray(16) { true })

        assertEquals(List(15) { null }, results.dropLast(1))
        assertEquals(16, results.last()?.size)
    }

    @Test
    fun `F20_4 말소리가 0점6초에서 멈추면 끊지 않는다`() {
        val results = feed(*BooleanArray(15) { true }, false, false, false, false, false, false, false)

        assertEquals(List(22) { null }, results)
    }

    @Test
    fun `F20_4 말소리 사이 짧은 틈은 이어진 것으로 보고 틈은 말소리 길이에 세지 않는다`() {
        val results = feed(*BooleanArray(8) { true }, false, false, *BooleanArray(8) { true })

        assertEquals(List(17) { null }, results.dropLast(1))
        assertEquals(18, results.last()?.size)
    }

    @Test
    fun `F20_4 말소리 한 조각과 짧은 틈이 되풀이되면 말소리가 0점6초가 될 때까지 끊지 않는다`() {
        val results = feed(*List(15) { listOf(true, false, false, false, false, false) }.flatten().toBooleanArray())

        assertEquals(List(90) { null }, results)
    }

    @Test
    fun `F20_4 틈이 길면 처음부터 다시 잰다`() {
        val results = feed(*BooleanArray(10) { true }, *BooleanArray(6) { false }, *BooleanArray(10) { true })

        assertEquals(List(26) { null }, results)
    }

    @Test
    fun `F20_4 말소리 앞의 말소리가 아닌 조각은 붙잡지 않는다`() {
        feed(*BooleanArray(30) { false })

        assertEquals(16, feed(*BooleanArray(16) { true }).last()?.size)
    }

    @Test
    fun `F20_4 비우면 쌓던 말소리를 버리고 처음부터 잰다`() {
        feed(*BooleanArray(15) { true })

        bargeIn.clear()

        assertNull(bargeIn.hold(byteArrayOf(), true, chunk))
    }
}
