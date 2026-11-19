package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard

/** 밀어서 뺀 카드를 지우고, 되돌리기면 같은 id 로 다시 쓴다(F17.7). */
@Inject
class RemoveCardUseCase(private val cards: CardStore) {
    suspend operator fun invoke(card: ExpressionCard, removed: Boolean) {
        if (removed) cards.delete(card.id) else cards.save(card)
    }
}
