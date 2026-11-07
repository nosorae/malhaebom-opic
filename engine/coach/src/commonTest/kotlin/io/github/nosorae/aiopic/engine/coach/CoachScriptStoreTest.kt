package io.github.nosorae.aiopic.engine.coach

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CoachScriptStoreTest {
    private fun script(sessionId: String) = CoachScript(sessionId, ScriptParts("i", "b", "c"), emptyList(), edited = null, editedAt = null, promptVersion = "v1", model = "m")

    @Test
    fun `F14_6 스크립트가 있는 세션만 최근 것부터 내고 같은 질문의 스크립트도 따로 낸다`() = runTest {
        val db = inMemoryDatabase(StandardTestDispatcher(testScheduler))
        val store = CoachStore(db)
        store.save(CoachState("old", "q1", "IH", Instant.fromEpochSeconds(1)))
        store.save(CoachState("new", "q1", "AL", Instant.fromEpochSeconds(3)))
        store.save(CoachState("talking", "q2", "IH", Instant.fromEpochSeconds(2)))
        store.save(script("old"))
        store.save(script("new"))

        val sessions = store.scriptSessions().first()

        assertEquals(listOf("new" to "AL", "old" to "IH"), sessions.map { it.id to it.targetGrade })
        db.close()
    }
}
