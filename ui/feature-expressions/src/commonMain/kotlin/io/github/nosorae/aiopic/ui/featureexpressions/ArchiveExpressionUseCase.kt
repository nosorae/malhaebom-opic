package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore

/** 필요 없어요는 보관함으로 보내 복습에서 빼고, 꺼내기는 다시 복습에 넣는다(F18.3). */
@Inject
class ArchiveExpressionUseCase(private val store: CardStore) {
    suspend operator fun invoke(id: Long, archived: Boolean) = store.archive(id, archived)
}
