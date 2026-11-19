package io.github.nosorae.aiopic.engine.review

import kotlin.time.Instant

/** 고칠 표현, 몰랐던 표현, 정형 표현(통째로 꺼내 쓰는 덩어리 표현), 더 자연스럽게(F17.1). */
enum class CardKind { Correction, UnknownExpression, Formulaic, MoreNatural }

/** 틀린 유형. 고칠 표현에만 붙는다. 한국어로 말한 것은 틀린 유형이 아니라 몰랐던 표현 카드가 된다(F16.23). */
enum class ErrorType { Tense, Article, WordChoice, Connector, SentenceStructure }

/**
 * 표현 카드 하나(design.md 12.3). 난이도와 안정도가 null 이면 FSRS 평가를 한 번도 받지 않은 카드다.
 * [firstSeen] 은 처음 봄이다. 만들 때 예이고 다음 복습 시각은 다음 날 새벽 4시([nextDayStart])다(F17.5).
 */
data class ExpressionCard(
    val id: Long = 0,
    val kind: CardKind,
    val said: String,
    val better: String,
    val meaning: String,
    val errorType: ErrorType?,
    val context: String,
    val createdAt: Instant,
    val dueAt: Instant,
    val difficulty: Double? = null,
    val stability: Double? = null,
    val lastReviewedAt: Instant? = null,
    val reviewCount: Int = 0,
    val againCount: Int = 0,
    val firstSeen: Boolean = true,
    val archived: Boolean = false,
    /** 카드를 만든 연습. 연습 기록이 지워져도 카드는 남는다. */
    val practiceId: String? = null,
)
