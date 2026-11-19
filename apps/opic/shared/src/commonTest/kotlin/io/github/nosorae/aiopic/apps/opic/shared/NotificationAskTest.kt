package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.engine.speaking.AudioRoute
import io.github.nosorae.aiopic.engine.speaking.SpeakingState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Android 와 iOS 앱 틀이 같은 조건으로 알림 권한을 묻는다(#229, F16.3). */
class NotificationAskTest {
    private val running = SpeakingState("p", Instant.parse("2026-11-20T10:00:00Z"), 20.minutes, AudioRoute.Earphones, "cooking")

    @Test
    fun `F16_3 분석을 한 번도 안 해도 말하기 연습이 돌기 시작하면 묻는다`() = runTest {
        val moments = notificationAskMoments(analyzing = flowOf(false), practice = flowOf(null, running), reminderOn = flowOf(false)).toList()

        assertTrue(moments.any { it })
    }

    @Test
    fun `끝난 연습이 남아 있는 것만으로는 묻지 않는다`() = runTest {
        val moments = notificationAskMoments(analyzing = flowOf(false), practice = flowOf(running.copy(finished = true)), reminderOn = flowOf(false)).toList()

        assertFalse(moments.any { it })
    }

    @Test
    fun `분석이 돌기 시작하면 묻는다`() = runTest {
        val moments = notificationAskMoments(analyzing = flowOf(true), practice = flowOf(null), reminderOn = flowOf(false)).toList()

        assertTrue(moments.any { it })
    }

    @Test
    fun `F19_2 복습 알림을 켜면 묻는다`() = runTest {
        val moments = notificationAskMoments(analyzing = flowOf(false), practice = flowOf(null), reminderOn = flowOf(false, true)).toList()

        assertTrue(moments.any { it })
    }
}
