package io.github.nosorae.aiopic.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ClockTextTest {
    @Test
    fun `분과 두 자리 초로 보인다`() {
        assertEquals("1:03", 63.seconds.clockText())
    }

    @Test
    fun `음수는 0초로 보인다`() {
        assertEquals("0:00", (-5).seconds.clockText())
    }
}
