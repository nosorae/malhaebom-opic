package io.github.nosorae.aiopic.engine.speaking

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 연습 기록에서 본 주제 하나(F16.5). [firstQuestion] 은 그 주제 마지막 연습의 첫 질문이다. */
data class TopicHistory(val topic: String, val lastAt: Instant, val count: Int, val firstQuestion: String?)

/** 고른 주제. [repeatQuestion] 이 있으면 지난번 첫 질문을 그대로 다시 묻는다. */
data class TopicPick(val topic: String, val repeatQuestion: String?)

private val RECALL_FROM = 7.days
private val RECALL_TO = 14.days

/**
 * 연습할 주제를 고른다(F16.5). 마지막 연습 뒤 7~14일 지난 주제가 있으면 마지막 연습이 가장 오래된 것을 지난번 첫 질문과 함께 고르고,
 * 없으면 [topics](서베이 주제와 업무 주제) 가운데 연습 기록에 가장 적게 나온 것을 목록 순서대로 고른다. "다른 주제" 는 방금 주제를 [exclude] 에 넣는다.
 */
fun pickTopic(topics: List<String>, history: List<TopicHistory>, now: Instant, exclude: Set<String> = emptySet()): TopicPick {
    history.filter { it.topic !in exclude && now - it.lastAt in RECALL_FROM..RECALL_TO }.minByOrNull { it.lastAt }?.let { return TopicPick(it.topic, it.firstQuestion) }
    val counts = history.associate { it.topic to it.count }
    val topic = topics.filter { it !in exclude }.minByOrNull { counts[it] ?: 0 } ?: topics.first()
    return TopicPick(topic, null)
}

/** 한 연습의 다룬 질문 가운데 [topic] 의 첫 질문. */
fun firstQuestion(questions: List<Asked>, topic: String): String? = questions.firstOrNull { it.topic == topic }?.text
