package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.localDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** 상세에 보일 카드와 다음 복습 날짜(앱의 하루, F18.11). */
data class ExpressionDetail(val card: ExpressionCard, val nextReview: LocalDate)

/**
 * 표현 상세를 연다(F18.3). 열 때 처음 봄을 끄고 복습으로 세지 않는다. 그 뒤로 카드가 바뀌면 다시 내고, 지우면 null 을 낸다.
 */
@Inject
class OpenExpressionUseCase(private val store: CardStore, private val timeZone: TimeZone) {
    operator fun invoke(id: Long): Flow<ExpressionDetail?> = flow {
        store.card(id)?.takeIf { it.firstSeen }?.let { store.save(it.copy(firstSeen = false)) }
        emitAll(store.cards().map { cards -> cards.firstOrNull { it.id == id }?.let { ExpressionDetail(it, LocalDate.fromEpochDays(it.dueAt.localDay(timeZone))) } })
    }
}
