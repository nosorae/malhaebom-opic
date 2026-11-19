package io.github.nosorae.aiopic.engine.speaking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TopicPickTest {
    private val now = Instant.parse("2026-11-19T09:00:00Z")
    private val topics = listOf("cooking", "music", "jogging", "business-trip")

    private fun past(topic: String, daysAgo: Int, count: Int = 1, first: String? = "First $topic?") = TopicHistory(topic, now - daysAgo.days, count, first)

    @Test
    fun `F16_5 마지막 연습 뒤 7일에서 14일 지난 주제 가운데 가장 오래된 것을 지난번 첫 질문과 함께 고른다`() {
        val history = listOf(past("cooking", 8), past("music", 13), past("jogging", 15))

        assertEquals(TopicPick("music", "First music?"), pickTopic(topics, history, now))
    }

    @Test
    fun `F16_5 7일이 안 됐거나 14일이 넘은 주제는 다시 내지 않고 가장 적게 한 주제를 고른다`() {
        val history = listOf(past("cooking", 6, count = 1), past("music", 15, count = 3), past("jogging", 2, count = 2))

        assertEquals(TopicPick("business-trip", null), pickTopic(topics, history, now))
    }

    @Test
    fun `F16_5 가장 적게 한 주제가 여럿이면 주제 목록 순서대로 고른다`() {
        val history = listOf(past("cooking", 1, count = 2))

        assertEquals("music", pickTopic(topics, history, now).topic)
    }

    @Test
    fun `F16_5 7일 경계는 포함한다`() {
        assertEquals("cooking", pickTopic(topics, listOf(TopicHistory("cooking", now - 7.days, 1, "Q?")), now).topic)
        assertEquals("music", pickTopic(topics, listOf(TopicHistory("cooking", now - 7.days + 1.hours, 1, "Q?")), now).topic)
    }

    @Test
    fun `F16_9 다른 주제는 같은 순서로 고르되 방금 주제는 뺀다`() {
        val history = listOf(past("cooking", 8), past("music", 13))

        assertEquals(TopicPick("cooking", "First cooking?"), pickTopic(topics, history, now, exclude = setOf("music")))
    }

    @Test
    fun `F16_5 지난번 첫 질문은 그 연습의 다룬 질문 가운데 그 주제의 첫 항목이다`() {
        val questions = listOf(Asked("Old?", QuestionKind.Description, "music"), Asked("Cook?", QuestionKind.Description, "cooking"), Asked("Why?", QuestionKind.Routine, "cooking"))

        assertEquals("Cook?", firstQuestion(questions, "cooking"))
    }
}
