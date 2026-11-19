package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.review.ExpressionCard
import io.github.nosorae.aiopic.engine.speaking.OralReview
import io.github.nosorae.aiopic.engine.speaking.SpeakingRecord
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 말로 복습 결과 한 줄(F17.10). [locked] 면 판정 뒤에 다시 복습했거나 카드를 지워서 판정을 바꿀 수 없다. */
data class OralRow(val review: OralReview, val locked: Boolean)

/** 연습 정리 화면의 값. [record] 가 null 이면 지운 연습이다. [cards] 는 그 연습에서 만든, 보관하지 않은 카드다. */
data class SpeakingSummary(val record: SpeakingRecord?, val cards: List<ExpressionCard>, val oral: List<OralRow>)

@Inject
class ObserveSpeakingSummaryUseCase(private val speaking: SpeakingStore, private val cards: CardStore) {
    operator fun invoke(practiceId: String): Flow<SpeakingSummary> = combine(speaking.sessions(), cards.cards()) { sessions, all ->
        val record = sessions.firstOrNull { it.id == practiceId }
        val byId = all.associateBy { it.id }
        SpeakingSummary(
            record = record,
            cards = all.filter { it.practiceId == practiceId && !it.archived },
            oral = record?.oralReviews.orEmpty().map { review ->
                val reviewedAt = byId[review.cardId]?.lastReviewedAt?.toEpochMilliseconds()
                OralRow(review, locked = reviewedAt == null || reviewedAt > review.judgedAtMs)
            },
        )
    }
}
