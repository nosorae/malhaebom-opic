package io.github.nosorae.aiopic.ui.featureexpressions

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.review.REVIEW_BATCH
import io.github.nosorae.aiopic.engine.review.Rating
import io.github.nosorae.aiopic.engine.review.todayQueue
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** 복습 화면이 여는 묶음. [remaining] 은 아직 누르지 않은 카드, [results] 는 이번 묶음에서 이미 누른 결과다. */
data class ReviewSession(val remaining: List<ExpressionCard>, val results: List<Rating?>, val silent: Boolean)

/**
 * 복습 묶음을 연다(F18.4, F18.8). 끝나지 않은 묶음이 있으면 남은 카드부터 이어 가고, 없거나 [more](6장 더)면 오늘 복습할 카드 앞 6장으로 새 묶음을 만든다.
 * 남은 카드 가운데 그 사이 지운 카드는 뺀다.
 */
// ponytail: 남은 카드를 지운 묶음은 id 가 남아 끝나지 않은 것으로 남는다. 다시 열면 남은 카드 없이 복습 끝을 보이고, 6장 더로 새 묶음을 연다.
@Inject
class StartReviewUseCase(private val store: CardStore, private val settings: ReviewSettings, private val clock: Clock, private val timeZone: TimeZone) {
    suspend operator fun invoke(more: Boolean = false): ReviewSession {
        val batch = settings.reviewBatch()?.takeUnless { more || it.finished }
            ?: ReviewBatch(todayQueue(store.cards().first(), clock.now(), timeZone).take(REVIEW_BATCH).map { it.id }).also { settings.saveReviewBatch(it) }
        return ReviewSession(batch.cardIds.drop(batch.results.size).mapNotNull { store.card(it) }, batch.results, settings.silent())
    }
}
