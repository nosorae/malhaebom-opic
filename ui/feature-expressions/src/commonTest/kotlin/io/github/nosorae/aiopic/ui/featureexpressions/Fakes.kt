package io.github.nosorae.aiopic.ui.featureexpressions

import io.github.nosorae.aiopic.engine.answer.store.OpicDatabase
import io.github.nosorae.aiopic.engine.review.CardKind
import io.github.nosorae.aiopic.engine.review.ErrorType
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.ReviewAmount
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/** 플랫폼마다 메모리 DB 를 만든다. [context] 에 테스트 디스패처를 주면 쿼리가 테스트 시계 위에서 돈다. */
expect fun inMemoryDatabase(context: CoroutineContext? = null): OpicDatabase

class FakeReviewSettings(var silent: Boolean = false, var batch: ReviewBatch? = null) : ReviewSettings {
    override suspend fun silent() = silent

    override suspend fun setSilent(on: Boolean) {
        silent = on
    }

    override suspend fun reviewAmount() = ReviewAmount.Normal

    override suspend fun reviewBatch() = batch

    override suspend fun saveReviewBatch(batch: ReviewBatch) {
        this.batch = batch
    }
}

class FakeVoice : ExpressionVoice {
    val spoken = mutableListOf<String>()

    override suspend fun speak(text: String) {
        spoken += text
    }
}

class FixedClock(var now: Instant) : Clock {
    override fun now() = now
}

fun card(createdAt: Instant, kind: CardKind = CardKind.Correction, firstSeen: Boolean = false, errorType: ErrorType? = ErrorType.Tense, archived: Boolean = false) = ExpressionCard(
    kind = kind,
    said = "I go to school yesterday.",
    better = "I went to school yesterday.",
    meaning = "어제 학교에 갔다.",
    errorType = errorType.takeIf { kind == CardKind.Correction },
    context = "주말에 한 일을 말하다가",
    createdAt = createdAt,
    dueAt = createdAt,
    firstSeen = firstSeen,
    archived = archived,
)
