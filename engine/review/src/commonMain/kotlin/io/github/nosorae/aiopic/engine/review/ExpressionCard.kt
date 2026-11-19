package io.github.nosorae.aiopic.engine.review

import kotlin.time.Instant

/** 고칠 표현, 몰랐던 표현, 단어. */
enum class CardKind { Correction, UnknownExpression, Word }

/** 틀린 유형. 고칠 표현에만 붙는다. */
enum class ErrorType { Tense, Article, WordChoice, Connector, SentenceStructure, SaidInKorean }

/**
 * 표현 카드 하나(design.md 12.3). 난이도와 안정도가 null 이면 한 번도 복습하지 않은 새 카드다.
 * 새 카드의 다음 복습 시각은 만든 시각으로 두어 바로 오늘 복습에 들어가게 한다.
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
    val archived: Boolean = false,
    /** 카드를 만든 연습. 연습 기록이 지워져도 카드는 남는다. */
    val practiceId: String? = null,
)
