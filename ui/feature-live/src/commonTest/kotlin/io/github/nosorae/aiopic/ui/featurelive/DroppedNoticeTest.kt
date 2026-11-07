package io.github.nosorae.aiopic.ui.featurelive

import io.github.nosorae.aiopic.engine.coach.TransportEvent.Disconnected
import kotlin.test.Test
import kotlin.test.assertEquals

class DroppedNoticeTest {
    private val reasons = listOf(Disconnected.MIC, Disconnected.INVALID_KEY, Disconnected.MODEL_UNAVAILABLE, Disconnected.RATE_LIMITED, Disconnected.NO_KEY, Disconnected.NETWORK)

    @Test
    fun `F11_9 끊긴 이유마다 다른 문구를 보인다`() {
        assertEquals(reasons.size, reasons.map { droppedMessage(it) }.toSet().size)
    }

    @Test
    fun `키가 없거나 틀려 끊기면 다시 연결 대신 키 설정으로 보낸다`() {
        assertEquals(listOf(Disconnected.INVALID_KEY, Disconnected.NO_KEY), reasons.filter { it.needsKeySettings })
    }
}
