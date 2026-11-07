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
    fun `F14_6 세션을 최근 것부터 질문 글, 상태, 스크립트가 있는지와 함께 내고 같은 질문의 세션도 따로 낸다`() = runTest {
        val db = inMemoryDatabase(StandardTestDispatcher(testScheduler))
        val store = CoachStore(db)
        store.save(CoachState("old", "q1", "Q1?", "IH", Instant.fromEpochSeconds(1), phase = CoachPhase.Script, status = CoachStatus.Finished))
        store.save(CoachState("new", "q1", "Q1?", "AL", Instant.fromEpochSeconds(4), phase = CoachPhase.Script, status = CoachStatus.Finished))
        store.save(CoachState("failed", "q2", "Q2?", "IH", Instant.fromEpochSeconds(3), phase = CoachPhase.Script, status = CoachStatus.Finished))
        store.save(CoachState("talking", "q2", "Q2?", "IH", Instant.fromEpochSeconds(2)))
        store.save(script("old"))
        store.save(script("new"))

        val sessions = store.sessions().first()

        assertEquals(
            listOf("new Q1? Finished true", "failed Q2? Finished false", "talking Q2? Active false", "old Q1? Finished true"),
            sessions.map { (session, written) -> "${session.id} ${session.question} ${session.status} $written" },
        )
        db.close()
    }
}
