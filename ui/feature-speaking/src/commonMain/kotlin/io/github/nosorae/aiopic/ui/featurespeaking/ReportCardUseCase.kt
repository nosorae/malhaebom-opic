package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject
import io.github.nosorae.aiopic.engine.review.CardStore
import io.github.nosorae.aiopic.engine.speaking.SpeakingStore

/** 틀린 지적 신고(F17.7). 카드를 지우고 연습 기록의 신고 수를 하나 올린다. 신고 수로 과잉 교정을 지켜본다(ADR 0041). */
@Inject
class ReportCardUseCase(private val speaking: SpeakingStore, private val cards: CardStore) {
    suspend operator fun invoke(practiceId: String, cardId: Long) {
        cards.delete(cardId)
        speaking.report(practiceId)
    }
}
