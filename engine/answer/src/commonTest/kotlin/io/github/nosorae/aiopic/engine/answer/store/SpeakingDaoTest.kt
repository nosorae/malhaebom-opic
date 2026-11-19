package io.github.nosorae.aiopic.engine.answer.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeakingDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.speakingDao()

    @AfterTest
    fun close() = db.close()

    @Test
    fun `F16_16 진행 중인 연습은 많아야 한 줄이라 새 연습을 저장하면 앞 줄이 지워진다`() = runTest {
        dao.saveActive(activeRow("p1"))

        dao.saveActive(activeRow("p2"))

        assertEquals("p2", dao.active()?.id)
    }

    @Test
    fun `F16_16 응답마다 같은 연습을 통째로 덮어쓴다`() = runTest {
        dao.saveActive(activeRow("p1"))

        dao.saveActive(activeRow("p1", phase = "Wrap"))

        assertEquals("Wrap", dao.active()?.phase)
    }

    @Test
    fun `F16_11 끝내면 진행 중인 줄이 지워지고 연습 기록이 남는다`() = runTest {
        dao.saveActive(activeRow("p1"))

        dao.finish(sessionRow("p1"))

        assertNull(dao.active())
        assertEquals(listOf("p1"), dao.sessions().first().map { it.id })
    }

    @Test
    fun `연습 기록은 최근 것부터 낸다`() = runTest {
        dao.save(sessionRow("old", startedAt = 1))
        dao.save(sessionRow("new", startedAt = 3))
        dao.save(sessionRow("mid", startedAt = 2))

        assertEquals(listOf("new", "mid", "old"), dao.sessions().first().map { it.id })
    }

    @Test
    fun `F16_5 주제마다 마지막 연습 시각과 연습 수와 그 연습의 다룬 질문을 낸다`() = runTest {
        dao.save(sessionRow("a", startedAt = 1, topic = "home", questions = "old"))
        dao.save(sessionRow("b", startedAt = 3, topic = "home", questions = "last"))
        dao.save(sessionRow("c", startedAt = 2, topic = "cooking", questions = "only"))

        assertEquals(setOf(TopicRow("home", 3, 2, "last"), TopicRow("cooking", 2, 1, "only")), dao.topics().toSet())
    }
}
