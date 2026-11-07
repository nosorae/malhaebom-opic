package io.github.nosorae.aiopic.ui.featurepractice

import io.github.nosorae.aiopic.engine.answer.weakness.CorrectionFlag
import io.github.nosorae.aiopic.engine.question.model.QuestionType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PickPracticeQuestionUseCaseTest {
    private val routine = question("r1", "routine")
    private val roleplay = question("p1", "roleplay")
    private val roleplay2 = question("p2", "roleplay")

    private suspend fun pick(repository: FakePracticeRepository) = PickPracticeQuestionUseCase(repository, typeNames = mapOf(QuestionType("roleplay") to "롤플레이"))()

    @Test
    fun `F14_5 질문 id 를 주면 약점과 상관없이 그 질문을 낸다`() = runTest {
        val repository = FakePracticeRepository(
            pool = listOf(routine, roleplay),
            answers = mutableListOf(scored("a1", routine, 80), scored("a2", roleplay, 40)),
        )

        assertEquals(PracticePick(routine, weakness = null), PickPracticeQuestionUseCase(repository, typeNames = emptyMap())("r1"))
    }

    @Test
    fun `F14_5 준 질문 id 가 풀에 없으면 다른 질문으로 바꾸지 않고 null 이다`() = runTest {
        val repository = FakePracticeRepository(pool = listOf(routine, roleplay))

        assertNull(PickPracticeQuestionUseCase(repository, typeNames = emptyMap())("gone"))
    }

    @Test
    fun `약점 점수가 가장 낮은 유형에서 낸다`() = runTest {
        val repository = FakePracticeRepository(
            pool = listOf(routine, roleplay),
            answers = mutableListOf(scored("a1", routine, 80), scored("a2", roleplay, 40)),
        )

        assertEquals("roleplay", pick(repository)?.question?.type?.id)
    }

    @Test
    fun `틀림 표시한 답변은 약점 점수에서 빠진다`() = runTest {
        val repository = FakePracticeRepository(
            pool = listOf(routine, roleplay),
            answers = mutableListOf(scored("a1", routine, 80), scored("a2", roleplay, 40)),
            flags = setOf(CorrectionFlag("a2", 0)),
        )

        assertEquals("routine", pick(repository)?.question?.type?.id)
    }

    @Test
    fun `약한 유형 안에서는 아직 답하지 않은 질문을 먼저 낸다`() = runTest {
        val repository = FakePracticeRepository(
            pool = listOf(routine, roleplay, roleplay2),
            answers = mutableListOf(scored("a1", routine, 80), scored("a2", roleplay, 40)),
        )

        assertEquals("p2", pick(repository)?.question?.id)
    }

    @Test
    fun `가장 약한 유형의 문항이 풀에 없으면 그다음 약한 유형에서 낸다`() = runTest {
        val repository = FakePracticeRepository(
            pool = listOf(routine),
            answers = mutableListOf(scored("a1", routine, 80), scored("a2", roleplay, 40)),
        )

        assertEquals("r1", pick(repository)?.question?.id)
    }

    @Test
    fun `고른 약한 유형의 이름과 점수를 함께 돌려준다`() = runTest {
        val repository = FakePracticeRepository(
            pool = listOf(routine, roleplay),
            answers = mutableListOf(scored("a1", routine, 80), scored("a2", roleplay, 42)),
        )

        assertEquals(PracticeWeakness("롤플레이", 42), pick(repository)?.weakness)
    }

    @Test
    fun `이름을 모르는 유형은 id 를 이름으로 쓴다`() = runTest {
        val repository = FakePracticeRepository(pool = listOf(routine), answers = mutableListOf(scored("a1", routine, 70)))

        assertEquals(PracticeWeakness("routine", 70), pick(repository)?.weakness)
    }

    @Test
    fun `분석된 답변이 없으면 약점 없이 낸다`() = runTest {
        assertNull(pick(FakePracticeRepository(pool = listOf(routine)))?.weakness)
    }

    @Test
    fun `분석된 답변이 없으면 풀 전체에서 낸다`() = runTest {
        assertEquals("r1", pick(FakePracticeRepository(pool = listOf(routine)))?.question?.id)
    }

    @Test
    fun `풀이 비었으면 null 이다`() = runTest {
        assertNull(pick(FakePracticeRepository(pool = emptyList())))
    }
}
