package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore

/** 고치기로 더 나은 표현과 한국어 뜻을 바꾼다(F18.3). 복습 값은 그대로 둔다. */
@Inject
class EditExpressionUseCase(private val store: CardStore) {
    suspend operator fun invoke(id: Long, better: String, meaning: String) {
        store.card(id)?.let { store.save(it.copy(better = better.trim(), meaning = meaning.trim())) }
    }
}
