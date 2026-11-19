package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore

/** 카드와 그 복습 기록을 지운다(F18.3). */
@Inject
class DeleteExpressionUseCase(private val store: CardStore) {
    suspend operator fun invoke(id: Long) = store.delete(id)
}
