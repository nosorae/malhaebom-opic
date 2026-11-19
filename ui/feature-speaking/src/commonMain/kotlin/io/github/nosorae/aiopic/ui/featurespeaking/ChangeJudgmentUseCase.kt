package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.review
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import io.github.nosorae.aiopic.ui.featureexpressions.ReviewSettings
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * 말로 복습 판정을 바꾼다(F17.10). 다시는 알았음으로, 알았음은 다시로 바꾼다. 카드의 복습 값을 판정 전 값으로 되돌리고
 * 판정 때 센 복습 횟수와 다시 누른 횟수를 뺀 뒤, 바뀐 판정으로 판정한 시각에 FSRS 를 다시 계산한다. 그래서 다시 누른 횟수는 하나 오르거나 내린다.
 * 판정 뒤에 다시 복습한 카드와 지운 카드는 바꾸지 않는다. 카드와 판정 목록은 한 트랜잭션으로 쓴다.
 */
@Inject
class ChangeJudgmentUseCase(private val speaking: SpeakingStore, private val cards: CardStore, private val settings: ReviewSettings) {
    suspend operator fun invoke(practiceId: String, cardId: Long) {
        val record = speaking.sessions().first().firstOrNull { it.id == practiceId } ?: return
        val judged = record.oralReviews.firstOrNull { it.cardId == cardId } ?: return
        val card = cards.card(cardId) ?: return
        if ((card.lastReviewedAt?.toEpochMilliseconds() ?: 0) > judged.judgedAtMs) return
        val before = judged.before
        val restored = card.copy(
            difficulty = before.difficulty,
            stability = before.stability,
            dueAt = Instant.fromEpochMilliseconds(before.dueAtMs),
            lastReviewedAt = before.lastReviewedAtMs?.let(Instant::fromEpochMilliseconds),
            reviewCount = card.reviewCount - 1,
            againCount = card.againCount - if (judged.correct) 0 else 1,
        )
        val correct = !judged.correct
        val changed = restored.review(if (correct) Rating.Good else Rating.Again, Instant.fromEpochMilliseconds(judged.judgedAtMs), settings.reviewAmount())
        speaking.saveJudgment(practiceId, changed, record.oralReviews.map { if (it.cardId == cardId) it.copy(correct = correct) else it })
    }
}
