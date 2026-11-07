package io.github.nosorae.aiopic.apps.opic.shared

import io.github.nosorae.aiopic.apps.opic.shared.data.inMemoryDatabase
import io.github.nosorae.aiopic.engine.coach.CoachState
import io.github.nosorae.aiopic.engine.coach.CoachStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CoachHistoryWiringTest {
    @Test
    fun `F14_6 기록 탭의 코치 줄은 문항 풀이 아니라 세션에 저장된 질문 글을 쓴다`() = runTest {
        val db = inMemoryDatabase()
        val store = CoachStore(db)
        store.save(CoachState("c1", "not-in-pool", "Tell me about a trip.", "IH", Instant.fromEpochSeconds(0)))

        val rows = scriptRows(store).first()

        assertEquals(listOf("Tell me about a trip."), rows.map { it.question })
        db.close()
    }
}
