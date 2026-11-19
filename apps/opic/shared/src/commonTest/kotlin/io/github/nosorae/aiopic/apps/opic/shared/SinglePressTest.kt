package io.github.nosorae.aiopic.apps.opic.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** 이어폰 버튼은 한 번 누르기만 받는다(F16.10). */
class SinglePressTest {
    @Test
    fun `F16_10 두 번 누르기의 뒷 누르기는 버리고 시간이 지난 누르기는 받는다`() {
        val press = SinglePress(window = 300)

        assertEquals(listOf(true, false, false, true), listOf(0L, 200L, 400L, 800L).map(press::accept))
    }
}
