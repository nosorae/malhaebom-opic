package io.github.nosorae.aiopic.ui.featurespeaking

import dev.zacsweers.metro.Inject

@Inject
class RetryCardsUseCase(private val retryCards: RetryCards) {
    suspend operator fun invoke(practiceId: String) = retryCards.retry(practiceId)
}
